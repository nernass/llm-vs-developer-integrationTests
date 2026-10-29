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

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonar.api.config.PropertyDefinitions;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.server.authentication.OAuth2IdentityProvider;
import org.sonar.api.server.authentication.UnauthorizedException;
import org.sonar.api.server.authentication.UserIdentity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class claudeIT_1 {

  private static final String CALLBACK_URL = "http://localhost/oauth/callback/bitbucket";

  private WireMockServer wireMock;

  private MapSettings settings;
  private BitbucketSettings bitbucketSettings;
  private UserIdentityFactory userIdentityFactory;
  private BitbucketScribeApi scribeApi;
  private BitbucketIdentityProvider underTest;

  @Before
  public void setUp() {
    wireMock = new WireMockServer(options().dynamicPort());
    wireMock.start();
    WireMock.configureFor("localhost", wireMock.port());

    settings = new MapSettings(new PropertyDefinitions(BitbucketSettings.definitions()));
    bitbucketSettings = new BitbucketSettings(settings.asConfig());
    userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
    scribeApi = new BitbucketScribeApi(bitbucketSettings);
    underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

    String baseUrl = format("http://localhost:%d", wireMock.port());
    settings.setProperty(BitbucketSettings.CONSUMER_KEY, "test_client_id");
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, "test_client_secret");
    settings.setProperty(BitbucketSettings.ENABLED, true);
    settings.setProperty(BitbucketSettings.API_URL, baseUrl);
    settings.setProperty(BitbucketSettings.WEB_URL, baseUrl);
  }

  @After
  public void tearDown() {
    wireMock.resetAll();
    wireMock.stop();
  }

  // ── Init phase tests ──────────────────────────────────────────────────

  @Test
  public void shouldRedirectToBitbucketAuthorizationUrlWithAccountScope() throws Exception {
    DumbInitContext context = new DumbInitContext("csrf-state-token");

    underTest.init(context);

    assertThat(context.redirectedTo)
      .startsWith(format("http://localhost:%d/site/oauth2/authorize", wireMock.port()))
      .contains("response_type=code")
      .contains("client_id=test_client_id")
      .contains("scope=" + encode("account", StandardCharsets.UTF_8.name()))
      .contains("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()));
  }

  @Test
  public void shouldIncludeCallbackUrlInAuthorizationRedirect() throws Exception {
    DumbInitContext context = new DumbInitContext("state-123");

    underTest.init(context);

    assertThat(context.redirectedTo)
      .contains("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()));
  }

  // ── Callback happy-path tests ─────────────────────────────────────────

  @Test
  public void shouldAuthenticateSuccessfullyWithUniqueLoginStrategy() {
    stubAccessToken();
    stubUserProfile("alice", "Alice Smith", "{alice-uuid}");
    stubPrimaryEmail("alice@example.com");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-1"));
    underTest.callback(ctx);

    assertThat(ctx.csrfStateVerified.get()).isFalse();
    assertThat(ctx.redirectedToRequestedPage.get()).isTrue();
    assertThat(ctx.userIdentity).isNotNull();
    assertThat(ctx.userIdentity.getLogin()).isEqualTo("alice@bitbucket");
    assertThat(ctx.userIdentity.getName()).isEqualTo("Alice Smith");
    assertThat(ctx.userIdentity.getEmail()).isEqualTo("alice@example.com");
    assertThat(ctx.userIdentity.getProviderId()).isEqualTo("{alice-uuid}");

    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/site/oauth2/access_token")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user/emails")));
  }

  @Test
  public void shouldAuthenticateWithProviderLoginStrategyWhenConfigured() {
    settings.setProperty(BitbucketSettings.LOGIN_STRATEGY, BitbucketSettings.LOGIN_STRATEGY_PROVIDER_LOGIN);

    stubAccessToken();
    stubUserProfile("bob", "Bob Jones", "{bob-uuid}");
    stubPrimaryEmail("bob@example.com");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-2"));
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getLogin()).isEqualTo("bob");
    assertThat(ctx.userIdentity.getName()).isEqualTo("Bob Jones");
    assertThat(ctx.userIdentity.getEmail()).isEqualTo("bob@example.com");
  }

  @Test
  public void shouldFallbackToUsernameWhenDisplayNameIsNull() {
    stubAccessToken();
    stubUserProfileRaw("{\"username\":\"nameless\",\"uuid\":\"{no-name-uuid}\"}");
    stubPrimaryEmail("nameless@example.com");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-3"));
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getName()).isEqualTo("nameless");
    assertThat(ctx.userIdentity.getLogin()).isEqualTo("nameless@bitbucket");
  }

  @Test
  public void shouldFallbackToUsernameWhenDisplayNameIsEmpty() {
    stubAccessToken();
    stubUserProfileRaw("{\"username\":\"emptyname\",\"display_name\":\"\",\"uuid\":\"{empty-uuid}\"}");
    stubPrimaryEmail("emptyname@example.com");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-4"));
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getName()).isEqualTo("emptyname");
  }

  @Test
  public void shouldSetNullEmailWhenEmailEndpointFails() {
    stubAccessToken();
    stubUserProfile("charlie", "Charlie", "{charlie-uuid}");
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user/emails"))
      .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-5"));
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getLogin()).isEqualTo("charlie@bitbucket");
    assertThat(ctx.userIdentity.getEmail()).isNull();
    assertThat(ctx.redirectedToRequestedPage.get()).isTrue();
  }

  @Test
  public void shouldNotVerifyCsrfStateDuringCallback() {
    stubAccessToken();
    stubUserProfile("dave", "Dave", "{dave-uuid}");
    stubPrimaryEmail("dave@example.com");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-6"));
    underTest.callback(ctx);

    assertThat(ctx.csrfStateVerified.get()).isFalse();
  }

  @Test
  public void shouldNotCallTeamsEndpointWhenNoTeamRestrictionConfigured() {
    stubAccessToken();
    stubUserProfile("eve", "Eve", "{eve-uuid}");
    stubPrimaryEmail("eve@example.com");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-7"));
    underTest.callback(ctx);

    assertThat(ctx.redirectedToRequestedPage.get()).isTrue();
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/2.0/teams")));
  }

  @Test
  public void shouldVerifyCorrectSequenceOfOutboundRequests() {
    stubAccessToken();
    stubUserProfile("frank", "Frank", "{frank-uuid}");
    stubPrimaryEmail("frank@example.com");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-8"));
    underTest.callback(ctx);

    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/site/oauth2/access_token")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user/emails")));
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/2.0/teams")));
  }

  // ── Error handling tests ──────────────────────────────────────────────

  @Test
  public void shouldThrowIllegalStateExceptionWhenUserProfileReturns500() {
    stubAccessToken();
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(aResponse().withStatus(500).withBody("{error}")));

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-err1"));

    assertThatThrownBy(() -> underTest.callback(ctx))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Can not get Bitbucket user profile. HTTP code: 500, response: {error}");

    assertThat(ctx.userIdentity).isNull();
    assertThat(ctx.redirectedToRequestedPage.get()).isFalse();
  }

  @Test
  public void shouldThrowIllegalStateExceptionWhenUserProfileReturns401() {
    stubAccessToken();
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-err2"));

    assertThatThrownBy(() -> underTest.callback(ctx))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Can not get Bitbucket user profile. HTTP code: 401");
  }

  @Test
  public void shouldThrowIllegalStateExceptionWhenUserProfileReturns403() {
    stubAccessToken();
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(aResponse().withStatus(403).withBody("{\"error\":\"forbidden\"}")));

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-err3"));

    assertThatThrownBy(() -> underTest.callback(ctx))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Can not get Bitbucket user profile. HTTP code: 403");
  }

  @Test
  public void shouldThrowIllegalStateWhenDisabledAndInitCalled() {
    settings.setProperty(BitbucketSettings.ENABLED, false);
    DumbInitContext context = new DumbInitContext("state");

    assertThatThrownBy(() -> underTest.init(context))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Bitbucket authentication is disabled");
  }

  // ── Team restriction tests ────────────────────────────────────────────

  @Test
  public void shouldAllowAuthenticationWhenUserBelongsToOneAllowedTeam() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"allowed-team", "other-allowed"});

    stubAccessToken();
    stubUserProfile("grace", "Grace", "{grace-uuid}");
    stubPrimaryEmail("grace@example.com");
    stubTeams("unrelated-team", "allowed-team");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-t1"));
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getLogin()).isEqualTo("grace@bitbucket");
    assertThat(ctx.redirectedToRequestedPage.get()).isTrue();
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/teams")));
  }

  @Test
  public void shouldAllowAuthenticationWhenUserBelongsToMultipleAllowedTeams() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"teamA", "teamB"});

    stubAccessToken();
    stubUserProfile("hank", "Hank", "{hank-uuid}");
    stubPrimaryEmail("hank@example.com");
    stubTeams("teamA", "teamB");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-t2"));
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getLogin()).isEqualTo("hank@bitbucket");
    assertThat(ctx.redirectedToRequestedPage.get()).isTrue();
  }

  @Test
  public void shouldRejectAuthenticationWhenUserBelongsToNonAllowedTeamsOnly() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"required-team"});

    stubAccessToken();
    stubUserProfile("ivan", "Ivan", "{ivan-uuid}");
    stubPrimaryEmail("ivan@example.com");
    stubTeams("wrong-team-1", "wrong-team-2");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-t3"));

    assertThatThrownBy(() -> underTest.callback(ctx))
      .isInstanceOf(UnauthorizedException.class)
      .hasMessage("User ivan is not part of restricted teams");

    assertThat(ctx.userIdentity).isNull();
    assertThat(ctx.redirectedToRequestedPage.get()).isFalse();
  }

  @Test
  public void shouldRejectAuthenticationWhenUserBelongsToZeroTeams() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"any-team"});

    stubAccessToken();
    stubUserProfile("judy", "Judy", "{judy-uuid}");
    stubPrimaryEmail("judy@example.com");
    stubTeams(/* no teams */);

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-t4"));

    assertThatThrownBy(() -> underTest.callback(ctx))
      .isInstanceOf(UnauthorizedException.class)
      .hasMessage("User judy is not part of restricted teams");
  }

  @Test
  public void shouldRejectAuthenticationWhenTeamsEndpointFails() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"required-team"});

    stubAccessToken();
    stubUserProfile("kate", "Kate", "{kate-uuid}");
    stubPrimaryEmail("kate@example.com");
    wireMock.stubFor(get(urlPathEqualTo("/2.0/teams"))
      .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"internal\"}")));

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-t5"));

    assertThatThrownBy(() -> underTest.callback(ctx))
      .isInstanceOf(UnauthorizedException.class)
      .hasMessage("User kate is not part of restricted teams");
  }

  @Test
  public void shouldMakeFourRequestsWhenTeamRestrictionIsEnabled() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"dev-team"});

    stubAccessToken();
    stubUserProfile("leo", "Leo", "{leo-uuid}");
    stubPrimaryEmail("leo@example.com");
    stubTeams("dev-team");

    DumbCallbackContext ctx = new DumbCallbackContext(newRequest("auth-code-t6"));
    underTest.callback(ctx);

    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/site/oauth2/access_token")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user/emails")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/teams")));
  }

  // ── Stub helpers ──────────────────────────────────────────────────────

  private void stubAccessToken() {
    wireMock.stubFor(post(urlPathEqualTo("/site/oauth2/access_token"))
      .willReturn(okJson("{\"access_token\":\"test-access-token-abc\",\"scope\":\"account\"}")));
  }

  private void stubUserProfile(String username, String displayName, String uuid) {
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(okJson(format(
        "{\"username\":\"%s\",\"display_name\":\"%s\",\"uuid\":\"%s\"}",
        username, displayName, uuid))));
  }

  private void stubUserProfileRaw(String jsonBody) {
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(okJson(jsonBody)));
  }

  private void stubPrimaryEmail(String email) {
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user/emails"))
      .willReturn(okJson(format(
        "{\"values\":[{\"active\":true,\"email\":\"%s\",\"is_primary\":true}]}", email))));
  }

  private void stubTeams(String... teamNames) {
    String teamsJson = Arrays.stream(teamNames)
      .map(t -> format("{\"username\":\"%s\"}", t))
      .collect(Collectors.joining(","));
    wireMock.stubFor(get(urlPathEqualTo("/2.0/teams"))
      .willReturn(okJson(format("{\"values\":[%s]}", teamsJson))));
  }

  // ── Request helper ────────────────────────────────────────────────────

  private static HttpServletRequest newRequest(String verifierCode) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("code")).thenReturn(verifierCode);
    return request;
  }

  // ── Test doubles ──────────────────────────────────────────────────────

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
    String redirectedTo = null;
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