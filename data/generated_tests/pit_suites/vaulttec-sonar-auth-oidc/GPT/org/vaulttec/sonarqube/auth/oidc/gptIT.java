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

import static java.net.URLEncoder.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.server.authentication.OAuth2IdentityProvider;
import org.sonar.api.server.authentication.UserIdentity;

public class gptIT extends AbstractOidcTest {

  private static final String SUBJECT = "afaba592-ac85-4cf1-8ec6-0058d157f882";
  private static final String PROFILE_ACCESS_TOKEN = "profile-fallback-access-token";
  private static final String GROUPS_ACCESS_TOKEN = "groups-fallback-access-token";

  private WireMockServer wireMockServer;
  private String issuerUri;
  private OidcClient oidcClient;
  private UserIdentityFactory userIdentityFactory;
  private OidcIdentityProvider underTest;

  @Before
  public void setUp() {
    wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMockServer.start();
    issuerUri = wireMockServer.baseUrl();
    stubProviderMetadata();
    configureProvider(OidcConfiguration.LOGIN_STRATEGY_DEFAULT_VALUE, true);
  }

  @After
  public void tearDown() {
    if (wireMockServer != null && wireMockServer.isRunning()) {
      wireMockServer.resetAll();
      wireMockServer.stop();
    }
  }

  @Test
  public void shouldRedirectBrowserToAuthorizationEndpointUsingDiscoveredProviderMetadata() throws Exception {
    DumbInitContext context = new DumbInitContext("generated-csrf-state");

    underTest.init(context);

    assertThat(context.redirectedTo).startsWith(issuerUri + "/protocol/openid-connect/auth");
    assertThat(context.redirectedTo).contains("response_type=code");
    assertThat(context.redirectedTo).contains("scope=" + encode("openid email profile", StandardCharsets.UTF_8.name()));
    assertThat(context.redirectedTo).contains("state=generated-csrf-state");
    wireMockServer.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/.well-known/openid-configuration")));
  }

  @Test
  public void shouldAuthenticateUsingClaimsFromIdTokenWithoutCallingUserInfoEndpoint() throws Exception {
    stubTokenResponse(successfulTokenResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest("state=" + STATE + "&code=" + VALID_CODE));

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, "john.doo");
    wireMockServer.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/protocol/openid-connect/token"))
        .withHeader("Authorization", WireMock.matching("Basic .+"))
        .withRequestBody(WireMock.containing("grant_type=authorization_code"))
        .withRequestBody(WireMock.containing("code=" + VALID_CODE))
        .withRequestBody(WireMock.containing("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()))));
    wireMockServer.verify(0,
        WireMock.getRequestedFor(WireMock.urlEqualTo("/protocol/openid-connect/userinfo")));
  }

  @Test
  public void shouldFetchUserInfoWhenIdTokenDoesNotContainProfileClaims() {
    stubTokenResponse(tokenResponseWithoutProfileClaims());
    stubUserInfoResponse(successfulUserInfoResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest("state=" + STATE + "&code=" + VALID_CODE));

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, "john.doo");
    wireMockServer.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", WireMock.equalTo("Bearer " + PROFILE_ACCESS_TOKEN)));
  }

  @Test
  public void shouldFetchUserInfoWhenIdTokenDoesNotContainGroupsClaim() {
    stubTokenResponse(tokenResponseWithoutGroupsClaim());
    stubUserInfoResponse(successfulUserInfoResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest("state=" + STATE + "&code=" + VALID_CODE));

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, "john.doo");
    wireMockServer.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", WireMock.equalTo("Bearer " + GROUPS_ACCESS_TOKEN)));
  }

  @Test
  public void shouldRespectUniqueLoginStrategyConfiguredInOidcConfiguration() {
    configureProvider(OidcConfiguration.LOGIN_STRATEGY_UNIQUE, true);
    stubTokenResponse(successfulTokenResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest("state=" + STATE + "&code=" + VALID_CODE));

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, SUBJECT + "@" + OidcIdentityProvider.KEY);
  }

  @Test
  public void shouldFailWhenTokenEndpointReturnsErrorResponse() {
    stubTokenErrorResponse();
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest("state=" + STATE + "&code=" + VALID_CODE));

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("Token request failed: {\"error_description\":\"Invalid resource owner credentials\","
          + "\"error\":\"invalid_grant\"}", e.getMessage());
    }

    assertFailedAuthentication(callbackContext);
    wireMockServer.verify(0,
        WireMock.getRequestedFor(WireMock.urlEqualTo("/protocol/openid-connect/userinfo")));
  }

  @Test
  public void shouldFailWhenUserInfoEndpointReturnsErrorDuringFallback() {
    stubTokenResponse(tokenResponseWithoutProfileClaims());
    stubUserInfoErrorResponse();
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest("state=" + STATE + "&code=" + VALID_CODE));

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("UserInfo request failed: {\"error\":\"invalid_token\"}", e.getMessage());
    }

    assertFailedAuthentication(callbackContext);
    wireMockServer.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", WireMock.equalTo("Bearer " + PROFILE_ACCESS_TOKEN)));
  }

  @Test
  public void shouldFailWhenCallbackContainsAuthenticationErrorResponse() {
    DumbCallbackContext callbackContext = new DumbCallbackContext(
        newAuthenticationRequest("error=invalid_request&error_description=the request is not valid or malformed"));

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("Authentication request failed: {\"error_description\":"
          + "\"the request is not valid or malformed\",\"error\":\"invalid_request\"}", e.getMessage());
    }

    assertFailedAuthentication(callbackContext);
    wireMockServer.verify(0,
        WireMock.postRequestedFor(WireMock.urlEqualTo("/protocol/openid-connect/token")));
  }

  private void configureProvider(String loginStrategy, boolean syncGroups) {
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
    oidcClient = new OidcClient(config);
    userIdentityFactory = new UserIdentityFactory(config);
    underTest = new OidcIdentityProvider(config, oidcClient, userIdentityFactory);
  }

  private void stubProviderMetadata() {
    wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/.well-known/openid-configuration"))
        .willReturn(WireMock.aResponse().withHeader("Content-Type", "application/json")
            .withBody(getProviderMetadata(issuerUri).toJSONObject().toJSONString())));
  }

  private void stubTokenResponse(String responseBody) {
    wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/protocol/openid-connect/token"))
        .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody(responseBody)));
  }

  private void stubTokenErrorResponse() {
    wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/protocol/openid-connect/token"))
        .willReturn(WireMock.aResponse().withStatus(500).withHeader("Content-Type", "application/json")
            .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid resource owner credentials\"}")));
  }

  private void stubUserInfoResponse(String responseBody) {
    wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/protocol/openid-connect/userinfo"))
        .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody(responseBody)));
  }

  private void stubUserInfoErrorResponse() {
    wireMockServer.stubFor(WireMock.get(WireMock.urlEqualTo("/protocol/openid-connect/userinfo"))
        .willReturn(WireMock.aResponse().withStatus(401).withHeader("Content-Type", "application/json")
            .withBody("{\"error\":\"invalid_token\"}")));
  }

  private void assertAuthenticated(DumbCallbackContext callbackContext, String expectedProviderLogin) {
    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    assertThat(callbackContext.userIdentity).isNotNull();
    assertThat(callbackContext.userIdentity.getProviderId()).isEqualTo(SUBJECT);
    assertThat(callbackContext.userIdentity.getProviderLogin()).isEqualTo(expectedProviderLogin);
    assertThat(callbackContext.userIdentity.getName()).isEqualTo("John Doo");
    assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john.doo@acme.com");
    assertThat(callbackContext.userIdentity.getGroups()).containsExactlyInAnyOrder("group1", "group2");
  }

  private void assertFailedAuthentication(DumbCallbackContext callbackContext) {
    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();
    assertThat(callbackContext.userIdentity).isNull();
  }

  private static HttpServletRequest newAuthenticationRequest(String queryString) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(request.getQueryString()).thenReturn(queryString);
    return request;
  }

  private static String successfulTokenResponse() {
    return oidcTokenResponse("id-token-access-token", buildIdToken(true, true));
  }

  private static String tokenResponseWithoutProfileClaims() {
    return oidcTokenResponse(PROFILE_ACCESS_TOKEN, buildIdToken(false, true));
  }

  private static String tokenResponseWithoutGroupsClaim() {
    return oidcTokenResponse(GROUPS_ACCESS_TOKEN, buildIdToken(true, false));
  }

  private static String oidcTokenResponse(String accessToken, String idToken) {
    return String.format(
        "{\"access_token\":\"%s\",\"refresh_token\":\"refresh-token\",\"scope\":\"openid email profile\",\"id_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":300}",
        accessToken, idToken);
  }

  private static String buildIdToken(boolean includeProfileClaims, boolean includeGroupsClaim) {
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(SUBJECT)
        .claim("email", "john.doo@acme.com");
    if (includeProfileClaims) {
      claims.claim("name", "John Doo");
      claims.claim("preferred_username", "john.doo");
      claims.claim("given_name", "John");
      claims.claim("family_name", "Doo");
    }
    if (includeGroupsClaim) {
      claims.claim("myGroups", Arrays.asList("group1", "group2"));
    }
    return new PlainJWT(claims.build()).serialize();
  }

  private static String successfulUserInfoResponse() {
    return String.format(
        "{\"sub\":\"%s\",\"name\":\"John Doo\",\"preferred_username\":\"john.doo\",\"email\":\"john.doo@acme.com\",\"email_verified\":true,\"myGroups\":[\"group1\",\"group2\"]}",
        SUBJECT);
  }

  private static class DumbCallbackContext implements OAuth2IdentityProvider.CallbackContext {
    final HttpServletRequest request;
    final AtomicBoolean csrfStateVerified = new AtomicBoolean(false);
    final AtomicBoolean redirectedToRequestedPage = new AtomicBoolean(false);
    UserIdentity userIdentity = null;

    private DumbCallbackContext(HttpServletRequest request) {
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

    @Override
    public void verifyCsrfState(String parameterName) {
    }
  }

  private static class DumbInitContext implements OAuth2IdentityProvider.InitContext {
    String redirectedTo;
    private final String generatedCsrfState;

    private DumbInitContext(String generatedCsrfState) {
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