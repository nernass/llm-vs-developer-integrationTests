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

public class BitbucketIdentityProviderIntegrationTest {

  private static final String CALLBACK_URL = "http://localhost/oauth/callback/bitbucket";
  private static final String VERIFIER_CODE = "the-verifier-code";

  @Rule
  public MockWebServer bitbucket = new MockWebServer();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private MapSettings settings;
  private BitbucketSettings bitbucketSettings;
  private UserIdentityFactory userIdentityFactory;
  private BitbucketScribeApi scribeApi;
  private BitbucketIdentityProvider underTest;

  @Before
  public void setUp() {
    settings = new MapSettings(new PropertyDefinitions(BitbucketSettings.definitions()));
    settings.setProperty(BitbucketSettings.CONSUMER_KEY, "the_id");
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, "the_secret");
    settings.setProperty(BitbucketSettings.ENABLED, true);
    settings.setProperty(BitbucketSettings.ALLOW_USERS_TO_SIGN_UP, true);
    settings.setProperty(BitbucketSettings.API_URL, format("http://%s:%d", bitbucket.getHostName(), bitbucket.getPort()));
    settings.setProperty(BitbucketSettings.WEB_URL, format("http://%s:%d", bitbucket.getHostName(), bitbucket.getPort()));
    settings.setProperty(BitbucketSettings.LOGIN_STRATEGY, BitbucketSettings.LOGIN_STRATEGY_UNIQUE);

    bitbucketSettings = new BitbucketSettings(settings.asConfig());
    userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
    scribeApi = new BitbucketScribeApi(bitbucketSettings);
    underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);
  }

  /**
   * First phase: SonarQube redirects browser to Bitbucket authentication form, requesting the
   * minimal access rights ("scope") to get user profile.
   */
  @Test
  public void redirect_browser_to_bitbucket_authentication_form() throws Exception {
    DumbInitContext context = new DumbInitContext("the-csrf-state");
    underTest.init(context);
    assertThat(context.redirectedTo)
      .startsWith(bitbucket.url("site/oauth2/authorize").toString())
      .contains("scope=" + encode(BitbucketIdentityProvider.REQUIRED_SCOPE, StandardCharsets.UTF_8.name()));
  }

  /**
   * Second phase: Bitbucket redirects browser to SonarQube at /oauth/callback/bitbucket?code={the verifier code}.
   * This SonarQube web service sends three requests to Bitbucket:
   * <ul>
   *   <li>get an access token</li>
   *   <li>get the profile (login, name) of the authenticated user</li>
   *   <li>get the emails of the authenticated user</li>
   * </ul>
   */
  @Test
  public void authenticate_successfully() throws Exception {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    HttpServletRequest request = newRequest(VERIFIER_CODE);
    DumbCallbackContext callbackContext = new DumbCallbackContext(request);
    underTest.callback(callbackContext);

    // Bitbucket OAuth2 does not support CSRF state verification
    assertThat(callbackContext.csrfStateVerified.get()).isFalse();
    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
    assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
    assertThat(callbackContext.userIdentity.getGroups()).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

    // Verify the requests sent to Bitbucket
    RecordedRequest accessTokenRequest = bitbucket.takeRequest();
    assertThat(accessTokenRequest.getPath()).startsWith("/site/oauth2/access_token");
    RecordedRequest userRequest = bitbucket.takeRequest();
    assertThat(userRequest.getPath()).startsWith("/2.0/user");
    RecordedRequest emailRequest = bitbucket.takeRequest();
    assertThat(emailRequest.getPath()).startsWith("/2.0/user/emails");
    // Do not request user teams when team restriction is disabled
    assertThat(bitbucket.getRequestCount()).isEqualTo(3);
  }

  @Test
  public void authenticate_successfully_with_provider_login_strategy() throws Exception {
    settings.setProperty(BitbucketSettings.LOGIN_STRATEGY, BitbucketSettings.LOGIN_STRATEGY_PROVIDER_LOGIN);
    bitbucketSettings = new BitbucketSettings(settings.asConfig());
    userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
    scribeApi = new BitbucketScribeApi(bitbucketSettings);
    underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    HttpServletRequest request = newRequest(VERIFIER_CODE);
    DumbCallbackContext callbackContext = new DumbCallbackContext(request);
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john");
    assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
    assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
  }

  @Test
  public void authenticate_successfully_when_no_email_returned() throws Exception {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newEmptyEmailResponse());

    HttpServletRequest request = newRequest(VERIFIER_CODE);
    DumbCallbackContext callbackContext = new DumbCallbackContext(request);
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
    assertThat(callbackContext.userIdentity.getEmail()).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
  }

  @Test
  public void authenticate_successfully_when_display_name_missing() throws Exception {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", null));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    HttpServletRequest request = newRequest(VERIFIER_CODE);
    DumbCallbackContext callbackContext = new DumbCallbackContext(request);
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getName()).isEqualTo("john");
    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
  }

  @Test
  public void callback_throws_ISE_if_error_when_requesting_user_profile() {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{error}"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest(VERIFIER_CODE));
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Can not get Bitbucket user profile. HTTP code: 500, response: {error}");
    underTest.callback(callbackContext);

    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.userIdentity).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();
  }

  @Test
  public void callback_throws_ISE_if_error_when_requesting_access_token() {
    bitbucket.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest(VERIFIER_CODE));
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Invalid grant");
    underTest.callback(callbackContext);
  }

  @Test
  public void callback_throws_ISE_if_error_when_requesting_emails() {
    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{error}"));

    DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest(VERIFIER_CODE));
    underTest.callback(callbackContext);

    // Should continue even if email request fails
    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.userIdentity.getEmail()).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
  }

  @Test
  public void allow_authentication_if_user_is_member_of_one_restricted_team() throws Exception {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, "team1,team2");
    bitbucketSettings = new BitbucketSettings(settings.asConfig());
    userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
    scribeApi = new BitbucketScribeApi(bitbucketSettings);
    underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse("team3", "team2"));

    HttpServletRequest request = newRequest(VERIFIER_CODE);
    DumbCallbackContext callbackContext = new DumbCallbackContext(request);
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
  }

  @Test
  public void allow_authentication_if_team_restriction_is_empty() throws Exception {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, "");
    bitbucketSettings = new BitbucketSettings(settings.asConfig());
    userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
    scribeApi = new BitbucketScribeApi(bitbucketSettings);
    underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

    HttpServletRequest request = newRequest(VERIFIER_CODE);
    DumbCallbackContext callbackContext = new DumbCallbackContext(request);
    underTest.callback(callbackContext);

    assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    assertThat(bitbucket.getRequestCount()).isEqualTo(3);
  }

  @Test
  public void forbid_authentication_if_user_is_not_member_of_one_restricted_team() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, "team1,team2");
    bitbucketSettings = new BitbucketSettings(settings.asConfig());
    userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
    scribeApi = new BitbucketScribeApi(bitbucketSettings);
    underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse("team3"));

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    DumbCallbackContext context = new DumbCallbackContext(newRequest(VERIFIER_CODE));
    underTest.callback(context);
  }

  @Test
  public void forbid_authentication_if_user_is_not_member_of_any_team() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, "team1,team2");
    bitbucketSettings = new BitbucketSettings(settings.asConfig());
    userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
    scribeApi = new BitbucketScribeApi(bitbucketSettings);
    underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

    bitbucket.enqueue(newSuccessfulAccessTokenResponse());
    bitbucket.enqueue(newUserResponse("john", "John"));
    bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
    bitbucket.enqueue(newTeamsResponse(/* no teams */));

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    DumbCallbackContext context = new DumbCallbackContext(newRequest(VERIFIER_CODE));
    underTest.callback(context);
  }

  @Test
  public void init_throws_ISE_if_disabled() {
    settings.setProperty(BitbucketSettings.ENABLED, false);
    bitbucketSettings = new BitbucketSettings(settings.asConfig());
    userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
    scribeApi = new BitbucketScribeApi(bitbucketSettings);
    underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Bitbucket authentication is disabled");

    DumbInitContext context = new DumbInitContext("the-csrf-state");
    underTest.init(context);
  }

  @Test
  public void is_enabled_returns_false_when_consumer_key_missing() {
    settings.setProperty(BitbucketSettings.ENABLED, true);
    settings.setProperty(BitbucketSettings.CONSUMER_KEY, (String) null);
    bitbucketSettings = new BitbucketSettings(settings.asConfig());

    assertThat(bitbucketSettings.isEnabled()).isFalse();
  }

  @Test
  public void is_enabled_returns_false_when_consumer_secret_missing() {
    settings.setProperty(BitbucketSettings.ENABLED, true);
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, (String) null);
    bitbucketSettings = new BitbucketSettings(settings.asConfig());

    assertThat(bitbucketSettings.isEnabled()).isFalse();
  }

  @Test
  public void is_enabled_returns_false_when_disabled() {
    settings.setProperty(BitbucketSettings.ENABLED, false);
    bitbucketSettings = new BitbucketSettings(settings.asConfig());

    assertThat(bitbucketSettings.isEnabled()).isFalse();
  }

  @Test
  public void is_enabled_returns_true_when_enabled_and_credentials_present() {
    settings.setProperty(BitbucketSettings.ENABLED, true);
    settings.setProperty(BitbucketSettings.CONSUMER_KEY, "key");
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, "secret");
    bitbucketSettings = new BitbucketSettings(settings.asConfig());

    assertThat(bitbucketSettings.isEnabled()).isTrue();
  }

  /**
   * Response sent by Bitbucket to SonarQube when generating an access token
   */
  private static MockResponse newSuccessfulAccessTokenResponse() {
    return new MockResponse().setBody("{\"access_token\":\"e72e16c7e42f292c6912e7710c838347ae178b4a\",\"scope\":\"user\"}");
  }

  /**
   * Response of https://api.bitbucket.org/2.0/user
   */
  private static MockResponse newUserResponse(String login, String name) {
    String displayName = name != null ? "\"" + name + "\"" : "null";
    return new MockResponse().setBody("{\"username\":\"" + login + "\", \"display_name\":" + displayName + "}");
  }

  /**
   * Response of https://api.bitbucket.org/2.0/teams?role=member
   */
  private static MockResponse newTeamsResponse(String... teams) {
    String s = Arrays.stream(teams)
      .map(team -> "{\"username\":\"" + team + "\"}")
      .collect(Collectors.joining(","));
    return new MockResponse().setBody("{\"values\":[" + s + "]}");
  }

  /**
   * Response of https://api.bitbucket.org/2.0/user/emails
   */
  private static MockResponse newPrimaryEmailResponse(String email) {
    return new MockResponse().setBody("{\"values\":[{\"active\": true,\"email\":\"" + email + "\",\"is_primary\": true}]}");
  }

  /**
   * Empty response of https://api.bitbucket.org/2.0/user/emails
   */
  private static MockResponse newEmptyEmailResponse() {
    return new MockResponse().setBody("{\"values\":[]}");
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
      throw new UnsupportedOperationException("not used");
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