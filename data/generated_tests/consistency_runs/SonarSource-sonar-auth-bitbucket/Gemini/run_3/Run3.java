package org.sonarqube.auth.bitbucket;

import com.github.scribejava.core.model.Verb;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.config.PropertyDefinitions;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.server.authentication.OAuth2IdentityProvider;
import org.sonar.api.server.authentication.UnauthorizedException;
import org.sonar.api.server.authentication.UserIdentity;

import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BitbucketAuthenticationIT {

  private static final String CALLBACK_URL = "http://localhost/oauth/callback/bitbucket";

  @Rule
  public MockWebServer bitbucket = new MockWebServer();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private MapSettings settings = new MapSettings(new PropertyDefinitions(BitbucketSettings.definitions()));
  private BitbucketSettings bitbucketSettings = new BitbucketSettings(settings.asConfig());
  private UserIdentityFactory userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
  private BitbucketScribeApi scribeApi = new BitbucketScribeApi(bitbucketSettings);
  private BitbucketIdentityProvider underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

  @Before
  public void setUp() {
    settings.setProperty(BitbucketSettings.CONSUMER_KEY, "the_id");
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, "the_secret");
    settings.setProperty(BitbucketSettings.ENABLED, true);
    settings.setProperty(BitbucketSettings.API_URL, format("http://%s:%d", bitbucket.getHostName(), bitbucket.getPort()));
    settings.setProperty(BitbucketSettings.WEB_URL, format("http://%s:%d", bitbucket.getHostName(), bitbucket.getPort()));
  }

  @Test
  public void verify_provider_metadata_is_correct() {
    assertThat(underTest.getKey()).isEqualTo("bitbucket");
    assertThat(underTest.getName()).isEqualTo("Bitbucket");
    assertThat(underTest.isEnabled()).isTrue();
    assertThat(underTest.allowsUsersToSignUp()).isTrue();
  }

  @Test
  public void provider_is_disabled_when_client_id_or_secret_is_missing() {
    settings.setProperty(BitbucketSettings.CONSUMER_KEY, (String) null);
    assertThat(underTest.isEnabled()).isFalse();
  }

  @Test
  public void scribe_api_provides_correct_endpoints_mapped_to_settings() {
    assertThat(scribeApi.getAccessTokenEndpoint()).isEqualTo(bitbucket.url("site/oauth2/access_token").toString());
    assertThat(scribeApi.getAccessTokenVerb()).isEqualTo(Verb.POST);
  }

  @Test
  public void redirect_browser_to_bitbucket_authentication_form_with_required_scope() throws Exception {
    DumbInitContext context = new DumbInitContext("the-csrf-state");
    underTest.init(context);

    assertThat(context.redirectedTo)
      .startsWith(bitbucket.url("site/oauth2/authorize").toString())
      .contains("scope=" + encode(BitbucketIdentityProvider.REQUIRED_SCOPE, StandardCharsets.UTF_8.name()))
      .contains("response_type=code")
      .contains("client_id=the_id");
  }

  @Test
  public void init_throws_IllegalStateException_if_provider_is_disabled() {
    settings.setProperty(BitbucketSettings.ENABLED, false);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Bitbucket authentication is disabled");

    underTest.init(new DumbInitContext("the-csrf-state"));
  }

  @Test
  public void authenticate_successfully_with_happy_path_flow() throws Exception {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John Doe"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    HttpServletRequest request = newRequest("the-verifier-code");
    DumbCallbackContext callbackContext = new DumbCallbackContext(request);
    
    underTest.callback(callbackContext);

    assertThat(callbackContext.csrfStateVerified.get()).isFalse();
    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.userIdentity.getName()).isEqualTo("John Doe");
    assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

    RecordedRequest accessTokenRequest = bitbucket.takeRequest();
    assertThat(accessTokenRequest.getPath()).startsWith("/site/oauth2/access_token");
    RecordedRequest userRequest = bitbucket.takeRequest();
    assertThat(userRequest.getPath()).startsWith("/2.0/user");
    RecordedRequest emailRequest = bitbucket.takeRequest();
    assertThat(emailRequest.getPath()).startsWith("/2.0/user/emails");
    
    assertThat(bitbucket.getRequestCount()).isEqualTo(3);
  }

  @Test
  public void authenticate_successfully_with_provider_login_strategy_uses_exact_username() {
    settings.setProperty(BitbucketSettings.LOGIN_STRATEGY, BitbucketSettings.LOGIN_STRATEGY_PROVIDER_LOGIN);

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john_doe", "John Doe"));
    bitbucket.enqueue(newPrimaryEmailResponse("john.doe@bitbucket.org"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john_doe");
    assertThat(callbackContext.userIdentity.getProviderLogin()).isEqualTo("john_doe");
  }

  @Test
  public void authenticate_uses_username_as_fallback_name_when_display_name_is_missing() {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", ""));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getName()).isEqualTo("john");
  }

  @Test
  public void authenticate_successfully_without_email_when_emails_request_fails() {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\": \"Internal Server Error\"}"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity).isNotNull();
    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.userIdentity.getEmail()).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
  }

  @Test
  public void callback_throws_IllegalStateException_when_user_profile_request_fails() {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\": \"Failed to retrieve profile\"}"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Can not get Bitbucket user profile. HTTP code: 500, response: {\"error\": \"Failed to retrieve profile\"}");
    
    underTest.callback(callbackContext);
  }

  @Test
  public void callback_throws_IllegalStateException_if_provider_is_disabled() {
    settings.setProperty(BitbucketSettings.ENABLED, false);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Bitbucket authentication is disabled");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  @Test
  public void allow_authentication_if_user_is_member_of_one_restricted_team() throws Exception {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse("team3", "team2")); 

    HttpServletRequest request = newRequest("the-verifier-code");
    DumbCallbackContext callbackContext = new DumbCallbackContext(request);
    
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

    bitbucket.takeRequest();
    bitbucket.takeRequest();
    bitbucket.takeRequest();
    
    RecordedRequest teamsRequest = bitbucket.takeRequest();
    assertThat(teamsRequest.getPath()).startsWith("/2.0/teams?role=member");
  }

  @Test
  public void forbid_authentication_if_user_is_not_member_of_any_restricted_teams() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse("team3", "team4"));

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
    underTest.callback(context);
  }

  @Test
  public void forbid_authentication_if_user_has_no_teams_when_restriction_is_enabled() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse());

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
    underTest.callback(context);
  }

  @Test
  public void forbid_authentication_if_teams_request_fails_while_restriction_is_enabled() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\": \"Teams unavailable\"}"));

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
    underTest.callback(context);
  }

  private static MockResponse newSuccessfulAccessTokenResponse() {
    return new MockResponse().setBody("{\"access_token\":\"e72e16c7e42f292c6912e7710c838347ae178b4a\",\"scope\":\"user\"}");
  }

  private static MockResponse newUserResponse(String login, String name) {
    return new MockResponse().setBody("{\"username\":\"" + login + "\", \"display_name\":\"" + name + "\"}");
  }

  private static MockResponse newTeamsResponse(String... teams) {
    String s = Arrays.stream(teams)
      .map(team -> "{\"username\":\"" + team + "\"}")
      .collect(Collectors.joining(","));
    return new MockResponse().setBody("{\"values\":[" + s + "]}");
  }

  private static MockResponse newPrimaryEmailResponse(String email) {
    return new MockResponse().setBody("{\"values\":[{\"active\": true,\"email\":\"" + email + "\",\"is_primary\": true}]}");
  }

  private static HttpServletRequest newRequest(String verifierCode) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("code")).thenReturn(verifierCode);
    return request;
  }

  private static class DumbCallbackContext implements OAuth2IdentityProvider.CallbackContext {
    final HttpServletRequest request;
    final AtomicBoolean csrfStateVerified = new AtomicBoolean(false);
    final AtomicBoolean redirectedToRequestedPage = new AtomicBoolean(false);
    UserIdentity userIdentity = null;

    public DumbCallbackContext(HttpServletRequest request) {
      this.request = request;
    }

    @Override
    public void verifyCsrfState() {
      this.csrfStateVerified.set(true);
    }

    @Override
    public void redirectToRequestedPage() {
      redirectedToRequestedPage.set(true);
    }

    @Override
    public void authenticate(UserIdentity userIdentity) {
      this.userIdentity = userIdentity;
    }

    @Override
    public String getCallbackUrl() {
      return CALLBACK_URL;
    }

    @Override
    public HttpServletRequest getRequest() {
      return request;
    }

    @Override
    public HttpServletResponse getResponse() {
      throw new UnsupportedOperationException("Response stub not configured for retrieval in test context");
    }
  }

  private static class DumbInitContext implements OAuth2IdentityProvider.InitContext {
    String redirectedTo = null;
    private final String generatedCsrfState;

    public DumbInitContext(String generatedCsrfState) {
      this.generatedCsrfState = generatedCsrfState;
    }

    @Override
    public String generateCsrfState() {
      return generatedCsrfState;
    }

    @Override
    public void redirectTo(String url) {
      this.redirectedTo = url;
    }

    @Override
    public String getCallbackUrl() {
      return CALLBACK_URL;
    }

    @Override
    public HttpServletRequest getRequest() {
      return null;
    }

    @Override
    public HttpServletResponse getResponse() {
      return null;
    }
  }
}