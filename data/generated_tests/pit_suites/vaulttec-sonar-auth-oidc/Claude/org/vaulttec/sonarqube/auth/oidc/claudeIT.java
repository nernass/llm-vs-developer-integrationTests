/*
 * OpenID Connect Authentication for SonarQube
 * Copyright (c) 2017 Torsten Juergeleit
 * mailto:torsten AT vaulttec DOT org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vaulttec.sonarqube.auth.oidc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static java.net.URLEncoder.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.server.authentication.OAuth2IdentityProvider;
import org.sonar.api.server.authentication.UserIdentity;

/**
 * Integration tests for the full OIDC authentication flow (init + callback)
 * exercising real {@link OidcIdentityProvider}, {@link OidcClient},
 * {@link UserIdentityFactory} and {@link OidcConfiguration} with WireMock
 * stubbing the external OpenID Connect provider HTTP endpoints.
 */
public class claudeIT extends AbstractOidcTest {

  private static final String SUBJECT = "afaba592-ac85-4cf1-8ec6-0058d157f882";

  private WireMockServer wireMock;
  private String issuerUri;

  private OidcClient oidcClient;
  private UserIdentityFactory userIdentityFactory;
  private OidcIdentityProvider underTest;

  @Before
  public void setUp() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
    issuerUri = wireMock.baseUrl();
    stubDiscoveryEndpoint();
    rebuildComponents(OidcConfiguration.LOGIN_STRATEGY_DEFAULT_VALUE, true);
  }

  @After
  public void tearDown() {
    wireMock.resetAll();
    wireMock.stop();
  }

  // ── init() tests ──────────────────────────────────────────────────────

  @Test
  public void shouldRedirectToIdpAuthorizationEndpointWithCorrectScope() throws Exception {
    StubInitContext ctx = new StubInitContext("csrf-123");

    underTest.init(ctx);

    assertThat(ctx.redirectedTo)
        .startsWith(issuerUri + "/protocol/openid-connect/auth")
        .contains("response_type=code")
        .contains("client_id=id")
        .contains("scope=" + encode("openid email profile", StandardCharsets.UTF_8.name()))
        .contains("state=csrf-123");
  }

  @Test
  public void shouldIncludeCsrfStateInRedirectUrl() throws Exception {
    StubInitContext ctx = new StubInitContext("unique-state-value");

    underTest.init(ctx);

    assertThat(ctx.redirectedTo).contains("state=unique-state-value");
  }

  @Test
  public void shouldThrowWhenInitCalledWhileDisabled() {
    settings = new MapSettings();
    settings.setProperty(OidcConfiguration.ENABLED, false);
    config = new OidcConfiguration(settings.asConfig());
    oidcClient = createSpyOidcClient();
    userIdentityFactory = new UserIdentityFactory(config);
    underTest = new OidcIdentityProvider(config, oidcClient, userIdentityFactory);

    StubInitContext ctx = new StubInitContext("state");

    assertThatThrownBy(() -> underTest.init(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disabled");
  }

  // ── callback() – happy path ───────────────────────────────────────────

  @Test
  public void shouldAuthenticateFromIdTokenClaimsWithoutCallingUserInfo() throws Exception {
    stubTokenEndpoint(tokenBody(fullIdToken(), "access-tok"));

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertSuccessfulAuth(ctx, "john.doo");
    wireMock.verify(1, postRequestedFor(urlEqualTo("/protocol/openid-connect/token"))
        .withRequestBody(containing("grant_type=authorization_code"))
        .withRequestBody(containing("code=" + VALID_CODE)));
    wireMock.verify(0, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo")));
  }

  @Test
  public void shouldAuthenticateAndSyncGroupsFromIdToken() throws Exception {
    stubTokenEndpoint(tokenBody(fullIdToken(), "tok"));

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getGroups()).containsExactlyInAnyOrder("group1", "group2");
  }

  @Test
  public void shouldVerifyCsrfStateDuringCallback() throws Exception {
    stubTokenEndpoint(tokenBody(fullIdToken(), "tok"));

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertThat(ctx.csrfStateVerified.get()).isTrue();
  }

  @Test
  public void shouldRedirectToRequestedPageAfterSuccessfulCallback() throws Exception {
    stubTokenEndpoint(tokenBody(fullIdToken(), "tok"));

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertThat(ctx.redirectedToRequestedPage.get()).isTrue();
  }

  // ── callback() – userinfo fallback ────────────────────────────────────

  @Test
  public void shouldFallBackToUserInfoEndpointWhenIdTokenMissesProfileClaims() throws Exception {
    String idToken = buildIdToken(false, true);
    stubTokenEndpoint(tokenBody(idToken, "bearer-for-userinfo"));
    stubUserInfoEndpoint(200, userInfoBody());

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertSuccessfulAuth(ctx, "john.doo");
    wireMock.verify(1, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", equalTo("Bearer bearer-for-userinfo")));
  }

  @Test
  public void shouldFallBackToUserInfoEndpointWhenIdTokenMissesGroupsClaim() throws Exception {
    String idToken = buildIdToken(true, false);
    stubTokenEndpoint(tokenBody(idToken, "bearer-groups"));
    stubUserInfoEndpoint(200, userInfoBody());

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertSuccessfulAuth(ctx, "john.doo");
    wireMock.verify(1, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", equalTo("Bearer bearer-groups")));
  }

  // ── callback() – login strategies ─────────────────────────────────────

  @Test
  public void shouldUseUniqueLoginStrategyWhenConfigured() throws Exception {
    rebuildComponents(OidcConfiguration.LOGIN_STRATEGY_UNIQUE, true);
    stubTokenEndpoint(tokenBody(fullIdToken(), "tok"));

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getProviderLogin()).isEqualTo(SUBJECT + "@oidc");
  }

  @Test
  public void shouldUseProviderIdLoginStrategyWhenConfigured() throws Exception {
    rebuildComponents(OidcConfiguration.LOGIN_STRATEGY_PROVIDER_ID, true);
    stubTokenEndpoint(tokenBody(fullIdToken(), "tok"));

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getProviderLogin()).isEqualTo(SUBJECT);
  }

  @Test
  public void shouldUseEmailLoginStrategyWhenConfigured() throws Exception {
    rebuildComponents(OidcConfiguration.LOGIN_STRATEGY_EMAIL, true);
    stubTokenEndpoint(tokenBody(fullIdToken(), "tok"));

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getProviderLogin()).isEqualTo("john.doo@acme.com");
  }

  // ── callback() – group sync disabled ──────────────────────────────────

  @Test
  public void shouldNotSyncGroupsWhenGroupSyncIsDisabled() throws Exception {
    rebuildComponents(OidcConfiguration.LOGIN_STRATEGY_DEFAULT_VALUE, false);
    stubTokenEndpoint(tokenBody(fullIdToken(), "tok"));

    StubCallbackContext ctx = newCallbackContext();
    underTest.callback(ctx);

    assertThat(ctx.userIdentity.getGroups()).isEmpty();
    assertThat(ctx.redirectedToRequestedPage.get()).isTrue();
  }

  // ── callback() – error paths ──────────────────────────────────────────

  @Test
  public void shouldThrowIseWhenTokenEndpointReturns500() {
    stubTokenEndpointError(500,
        "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid resource owner credentials\"}");

    StubCallbackContext ctx = newCallbackContext();

    assertThatThrownBy(() -> underTest.callback(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Token request failed")
        .hasMessageContaining("invalid_grant");

    assertFailedAuth(ctx);
    wireMock.verify(0, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo")));
  }

  @Test
  public void shouldThrowIseWhenTokenEndpointReturns400() {
    stubTokenEndpointError(400, "{\"error\":\"invalid_request\"}");

    StubCallbackContext ctx = newCallbackContext();

    assertThatThrownBy(() -> underTest.callback(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Token request failed");

    assertFailedAuth(ctx);
  }

  @Test
  public void shouldThrowIseWhenUserInfoEndpointReturnsError() {
    stubTokenEndpoint(tokenBody(buildIdToken(false, true), "bad-bearer"));
    stubUserInfoEndpoint(401, "{\"error\":\"invalid_token\"}");

    StubCallbackContext ctx = newCallbackContext();

    assertThatThrownBy(() -> underTest.callback(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UserInfo request failed");

    assertFailedAuth(ctx);
  }

  @Test
  public void shouldThrowIseWhenCallbackContainsAuthenticationError() {
    HttpServletRequest errorRequest = mockRequest(
        "error=invalid_request&error_description=the+request+is+not+valid+or+malformed");

    StubCallbackContext ctx = new StubCallbackContext(errorRequest);

    assertThatThrownBy(() -> underTest.callback(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Authentication request failed")
        .hasMessageContaining("invalid_request");

    assertFailedAuth(ctx);
    wireMock.verify(0, postRequestedFor(urlEqualTo("/protocol/openid-connect/token")));
  }

  @Test
  public void shouldStillVerifyCsrfEvenWhenTokenRequestFails() {
    stubTokenEndpointError(500, "{\"error\":\"server_error\"}");

    StubCallbackContext ctx = newCallbackContext();

    assertThatThrownBy(() -> underTest.callback(ctx))
        .isInstanceOf(IllegalStateException.class);

    assertThat(ctx.csrfStateVerified.get()).isTrue();
  }

  // ── WireMock stub helpers ─────────────────────────────────────────────

  private void stubDiscoveryEndpoint() {
    wireMock.stubFor(get(urlEqualTo("/.well-known/openid-configuration"))
        .willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody(getProviderMetadata(issuerUri).toJSONObject().toJSONString())));
  }

  private void stubTokenEndpoint(String body) {
    wireMock.stubFor(post(urlEqualTo("/protocol/openid-connect/token"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(body)));
  }

  private void stubTokenEndpointError(int status, String body) {
    wireMock.stubFor(post(urlEqualTo("/protocol/openid-connect/token"))
        .willReturn(aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(body)));
  }

  private void stubUserInfoEndpoint(int status, String body) {
    wireMock.stubFor(get(urlEqualTo("/protocol/openid-connect/userinfo"))
        .willReturn(aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(body)));
  }

  // ── Configuration / wiring helpers ────────────────────────────────────

  private void rebuildComponents(String loginStrategy, boolean syncGroups) {
    settings = new MapSettings();
    settings.setProperty(OidcConfiguration.ENABLED, true);
    settings.setProperty(OidcConfiguration.ISSUER_URI, issuerUri);
    settings.setProperty(OidcConfiguration.CLIENT_ID, "id");
    settings.setProperty(OidcConfiguration.CLIENT_SECRET, "secret");
    settings.setProperty(OidcConfiguration.LOGIN_STRATEGY, loginStrategy);
    settings.setProperty(OidcConfiguration.GROUPS_SYNC, syncGroups);
    settings.setProperty(OidcConfiguration.GROUPS_SYNC_CLAIM_NAME, "myGroups");
    settings.setProperty(OidcConfiguration.SCOPES, "openid email profile");
    config = new OidcConfiguration(settings.asConfig());
    oidcClient = createSpyOidcClient();
    userIdentityFactory = new UserIdentityFactory(config);
    underTest = new OidcIdentityProvider(config, oidcClient, userIdentityFactory);
  }

  // ── Token / ID-token builders ─────────────────────────────────────────

  private static String fullIdToken() {
    return buildIdToken(true, true);
  }

  private static String buildIdToken(boolean includeProfile, boolean includeGroups) {
    JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
        .subject(SUBJECT)
        .claim("email", "john.doo@acme.com");
    if (includeProfile) {
      b.claim("name", "John Doo")
       .claim("preferred_username", "john.doo")
       .claim("given_name", "John")
       .claim("family_name", "Doo");
    }
    if (includeGroups) {
      b.claim("myGroups", Arrays.asList("group1", "group2"));
    }
    return new PlainJWT(b.build()).serialize();
  }

  private static String tokenBody(String idToken, String accessToken) {
    return String.format(
        "{\"access_token\":\"%s\",\"refresh_token\":\"refresh\","
            + "\"scope\":\"openid email profile\","
            + "\"id_token\":\"%s\","
            + "\"token_type\":\"Bearer\",\"expires_in\":300}",
        accessToken, idToken);
  }

  private static String userInfoBody() {
    return String.format(
        "{\"sub\":\"%s\",\"name\":\"John Doo\",\"preferred_username\":\"john.doo\","
            + "\"email\":\"john.doo@acme.com\",\"email_verified\":true,"
            + "\"myGroups\":[\"group1\",\"group2\"]}",
        SUBJECT);
  }

  // ── Request / assertion helpers ───────────────────────────────────────

  private StubCallbackContext newCallbackContext() {
    return new StubCallbackContext(mockRequest("state=" + STATE + "&code=" + VALID_CODE));
  }

  private static HttpServletRequest mockRequest(String queryString) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(req.getQueryString()).thenReturn(queryString);
    return req;
  }

  private void assertSuccessfulAuth(StubCallbackContext ctx, String expectedLogin) {
    assertThat(ctx.csrfStateVerified.get()).isTrue();
    assertThat(ctx.redirectedToRequestedPage.get()).isTrue();
    assertThat(ctx.userIdentity).isNotNull();
    assertThat(ctx.userIdentity.getProviderId()).isEqualTo(SUBJECT);
    assertThat(ctx.userIdentity.getProviderLogin()).isEqualTo(expectedLogin);
    assertThat(ctx.userIdentity.getName()).isEqualTo("John Doo");
    assertThat(ctx.userIdentity.getEmail()).isEqualTo("john.doo@acme.com");
    assertThat(ctx.userIdentity.getGroups()).containsExactlyInAnyOrder("group1", "group2");
  }

  private static void assertFailedAuth(StubCallbackContext ctx) {
    assertThat(ctx.csrfStateVerified.get()).isTrue();
    assertThat(ctx.redirectedToRequestedPage.get()).isFalse();
    assertThat(ctx.userIdentity).isNull();
  }

  // ── Stub context implementations ─────────────────────────────────────

  private static class StubCallbackContext implements OAuth2IdentityProvider.CallbackContext {
    final HttpServletRequest request;
    final AtomicBoolean csrfStateVerified = new AtomicBoolean(false);
    final AtomicBoolean redirectedToRequestedPage = new AtomicBoolean(false);
    UserIdentity userIdentity;

    StubCallbackContext(HttpServletRequest request) {
      this.request = request;
    }

    @Override
    public void verifyCsrfState() {
      csrfStateVerified.set(true);
    }

    @Override
    public void verifyCsrfState(String parameterName) {
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

  private static class StubInitContext implements OAuth2IdentityProvider.InitContext {
    String redirectedTo;
    private final String csrfState;

    StubInitContext(String csrfState) {
      this.csrfState = csrfState;
    }

    @Override
    public String generateCsrfState() {
      return csrfState;
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
