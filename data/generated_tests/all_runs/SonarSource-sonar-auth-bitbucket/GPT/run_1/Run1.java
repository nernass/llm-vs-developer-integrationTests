/*
 * Bitbucket Authentication for SonarQube
 * Copyright (C) 2016-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
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
  public void provider_exposes_expected_metadata_and_defaults() {
    assertThat(underTest.getKey()).isEqualTo(BitbucketIdentityProvider.KEY);
    assertThat(underTest.getName()).isEqualTo("Bitbucket");
    assertThat(underTest.getDisplay().getIconPath()).isEqualTo("/static/authbitbucket/bitbucket.svg");
    assertThat(underTest.getDisplay().getBackgroundColor()).isEqualTo("#0052cc");
    assertThat(underTest.isEnabled()).isTrue();
    assertThat(underTest.allowsUsersToSignUp()).isTrue();
  }

  @Test
  public void provider_respects_allow_users_to_sign_up_setting() {
    settings.setProperty(BitbucketSettings.ALLOW_USERS_TO_SIGN_UP, false);

    assertThat(underTest.allowsUsersToSignUp()).isFalse();
  }

  @Test
  public void provider_is_disabled_when_client_id_is_missing() {
    settings.setProperty(BitbucketSettings.CONSUMER_KEY, (String) null);

    assertThat(underTest.isEnabled()).isFalse();
  }

  @Test
  public void provider_is_disabled_when_client_secret_is_missing() {
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, (String) null);

    assertThat(underTest.isEnabled()).isFalse();
  }

  @Test
  public void scribe_api_uses_expected_access_token_endpoint_and_verb() {
    assertThat(scribeApi.getAccessTokenEndpoint()).isEqualTo(bitbucket.url("site/oauth2/access_token").toString());
    assertThat(scribeApi.getAccessTokenVerb()).isEqualTo(Verb.POST);
  }

  @Test
  public void redirect_browser_to_bitbucket_authentication_form() throws Exception {
    DumbInitContext context = new DumbInitContext("the-csrf-state");

    underTest.init(context);

    assertThat(context.redirectedTo)
      .startsWith(bitbucket.url("site/oauth2/authorize").toString())
      .contains("response_type=code")
      .contains("client_id=the_id")
      .contains("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()))
      .contains("scope=" + encode(BitbucketIdentityProvider.REQUIRED_SCOPE, StandardCharsets.UTF_8.name()));
  }

  @Test
  public void init_throws_ISE_if_provider_is_disabled() {
    settings.setProperty(BitbucketSettings.ENABLED, false);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Bitbucket authentication is disabled");

    underTest.init(new DumbInitContext("the-csrf-state"));
  }

  @Test
  public void authenticate_successfully() throws Exception {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John", "abcd-1234"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(callbackContext);

    assertThat(callbackContext.csrfStateVerified.get()).isFalse();
    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
    assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
    assertThat(callbackContext.userIdentity.getProviderId()).isEqualTo("abcd-1234");
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

    RecordedRequest accessTokenRequest = bitbucket.takeRequest();
    assertThat(accessTokenRequest.getMethod()).isEqualTo("POST");
    assertThat(accessTokenRequest.getPath()).startsWith("/site/oauth2/access_token");

    RecordedRequest userRequest = bitbucket.takeRequest();
    assertThat(userRequest.getMethod()).isEqualTo("GET");
    assertThat(userRequest.getPath()).isEqualTo("/2.0/user");

    RecordedRequest emailRequest = bitbucket.takeRequest();
    assertThat(emailRequest.getMethod()).isEqualTo("GET");
    assertThat(emailRequest.getPath()).isEqualTo("/2.0/user/emails");

    assertThat(bitbucket.getRequestCount()).isEqualTo(3);
  }

  @Test
  public void authenticate_successfully_with_provider_login_strategy() {
    settings.setProperty(BitbucketSettings.LOGIN_STRATEGY, BitbucketSettings.LOGIN_STRATEGY_PROVIDER_LOGIN);

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John", "abcd-1234"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john");
    assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
    assertThat(callbackContext.userIdentity.getProviderId()).isEqualTo("abcd-1234");
  }

  @Test
  public void authenticate_uses_username_when_display_name_is_missing() {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "", "abcd-1234"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getName()).isEqualTo("john");
  }

  @Test
  public void authenticate_without_email_when_email_request_fails() {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John", "abcd-1234"));
    bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{error}"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.userIdentity.getEmail()).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    assertThat(bitbucket.getRequestCount()).isEqualTo(3);
  }

  @Test
  public void callback_throws_ISE_if_error_when_requesting_user_profile() {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{error}"));

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Can not get Bitbucket user profile. HTTP code: 500, response: {error}");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  @Test
  public void callback_throws_ISE_if_provider_is_disabled() {
    settings.setProperty(BitbucketSettings.ENABLED, false);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Bitbucket authentication is disabled");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  @Test
  public void allow_authentication_if_user_is_member_of_one_restricted_team() throws Exception {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John", "abcd-1234"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse("team3", "team2"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

    bitbucket.takeRequest();
    bitbucket.takeRequest();
    bitbucket.takeRequest();
    RecordedRequest teamsRequest = bitbucket.takeRequest();
    assertThat(teamsRequest.getMethod()).isEqualTo("GET");
    assertThat(teamsRequest.getPath()).isEqualTo("/2.0/teams?role=member");

    assertThat(bitbucket.getRequestCount()).isEqualTo(4);
  }

  @Test
  public void forbid_authentication_if_user_is_not_member_of_one_restricted_team() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John", "abcd-1234"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse("team3"));

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  @Test
  public void forbid_authentication_if_user_is_not_member_of_any_team() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John", "abcd-1234"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse());

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  @Test
  public void forbid_authentication_if_teams_request_fails_when_restriction_is_enabled() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John", "abcd-1234"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{error}"));

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  private static MockResponse newSuccessfulAccessTokenResponse() {
    return new MockResponse().setBody("{\"access_token\":\"e72e16c7e42f292c6912e7710c838347ae178b4a\",\"scope\":\"user\"}");
  }

  private static MockResponse newUserResponse(String login, String name, String uuid) {
    return new MockResponse().setBody("{\"username\":\"" + login + "\",\"display_name\":\"" + name + "\",\"uuid\":\"" + uuid + "\"}");
  }

  private static MockResponse newTeamsResponse(String... teams) {
    String values = Arrays.stream(teams)
      .map(team -> "{\"username\":\"" + team + "\"}")
      .collect(Collectors.joining(","));
    return new MockResponse().setBody("{\"values\":[" + values + "]}");
  }

  private static MockResponse newPrimaryEmailResponse(String email) {
    return new MockResponse().setBody("{\"values\":[{\"active\":true,\"email\":\"" + email + "\",\"is_primary\":true}]}");
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

    DumbCallbackContext(HttpServletRequest request) {
      this.request = request;
    }

    @Override
    public void verifyCsrfState() {
      csrfStateVerified.set(true);
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
      throw new UnsupportedOperationException("not used");
    }
  }

  private static class DumbInitContext implements OAuth2IdentityProvider.InitContext {
    String redirectedTo;
    private final String generatedCsrfState;

    DumbInitContext(String generatedCsrfState) {
      this.generatedCsrfState = generatedCsrfState;
    }

    @Override
    public String generateCsrfState() {
      return generatedCsrfState;
    }

    @Override
    public void redirectTo(String url) {
      redirectedTo = url;
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