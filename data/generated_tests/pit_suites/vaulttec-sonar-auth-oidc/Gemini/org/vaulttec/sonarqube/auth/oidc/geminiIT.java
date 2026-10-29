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

public class geminiIT extends AbstractOidcTest {

  private static final String SUBJECT = "afaba592-ac85-4cf1-8ec6-0058d157f882";
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

    wireMockServer.stubFor(get(urlEqualTo("/.well-known/openid-configuration"))
        .willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody(getProviderMetadata(issuerUri).toJSONObject().toJSONString())));

    initializeComponents(OidcConfiguration.LOGIN_STRATEGY_DEFAULT_VALUE, true);
  }

  @After
  public void tearDown() {
    if (wireMockServer != null && wireMockServer.isRunning()) {
      wireMockServer.resetAll();
      wireMockServer.stop();
    }
  }

  @Test
  public void shouldRedirectBrowserToAuthorizationEndpoint() throws Exception {
    TestInitContext context = new TestInitContext("test-csrf-state");

    underTest.init(context);

    assertThat(context.redirectUrl)
        .startsWith(issuerUri + "/protocol/openid-connect/auth")
        .contains("response_type=code")
        .contains("client_id=id")
        .contains("scope=" + encode("openid email profile", StandardCharsets.UTF_8.name()))
        .contains("state=test-csrf-state");
  }

  @Test
  public void shouldAuthenticateAutomaticallyWhenIdTokenHasAllClaims() throws Exception {
    stubTokenResponse(createTokenResponseBody(buildIdToken(true, true), "access-token-123"));
    TestCallbackContext context = createCallbackContext();

    underTest.callback(context);

    assertSuccessfulAuthentication(context, "john.doo");
    wireMockServer.verify(1, postRequestedFor(urlEqualTo("/protocol/openid-connect/token"))
        .withRequestBody(containing("grant_type=authorization_code"))
        .withRequestBody(containing("code=" + VALID_CODE)));
    wireMockServer.verify(0, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo")));
  }

  @Test
  public void shouldFetchUserinfoWhenProfileClaimsMissingFromIdToken() throws Exception {
    stubTokenResponse(createTokenResponseBody(buildIdToken(false, true), "fallback-token"));
    stubUserInfoResponse(200, createUserInfoResponseBody());
    TestCallbackContext context = createCallbackContext();

    underTest.callback(context);

    assertSuccessfulAuthentication(context, "john.doo");
    wireMockServer.verify(1, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", equalTo("Bearer fallback-token")));
  }

  @Test
  public void shouldFetchUserinfoWhenGroupsClaimMissingFromIdToken() throws Exception {
    stubTokenResponse(createTokenResponseBody(buildIdToken(true, false), "groups-token"));
    stubUserInfoResponse(200, createUserInfoResponseBody());
    TestCallbackContext context = createCallbackContext();

    underTest.callback(context);

    assertSuccessfulAuthentication(context, "john.doo");
    wireMockServer.verify(1, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", equalTo("Bearer groups-token")));
  }

  @Test
  public void shouldUseEmailLoginStrategyWhenConfigured() throws Exception {
    initializeComponents(OidcConfiguration.LOGIN_STRATEGY_EMAIL, true);
    stubTokenResponse(createTokenResponseBody(buildIdToken(true, true), "tok"));
    TestCallbackContext context = createCallbackContext();

    underTest.callback(context);

    assertSuccessfulAuthentication(context, "john.doo@acme.com");
  }

  @Test
  public void shouldFailAuthenticationWhenTokenEndpointReturnsError() {
    wireMockServer.stubFor(post(urlEqualTo("/protocol/openid-connect/token"))
        .willReturn(aResponse()
            .withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid credentials\"}")));
    
    TestCallbackContext context = createCallbackContext();

    assertThatThrownBy(() -> underTest.callback(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Token request failed");

    assertThat(context.userIdentity).isNull();
    assertThat(context.redirectedToRequestedPage.get()).isFalse();
    assertThat(context.csrfStateVerified.get()).isTrue();
  }

  @Test
  public void shouldFailAuthenticationWhenUserInfoEndpointReturnsError() {
    stubTokenResponse(createTokenResponseBody(buildIdToken(false, false), "invalid-bearer"));
    stubUserInfoResponse(401, "{\"error\":\"invalid_token\"}");
    
    TestCallbackContext context = createCallbackContext();

    assertThatThrownBy(() -> underTest.callback(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UserInfo request failed");
        
    assertThat(context.userIdentity).isNull();
  }

  @Test
  public void shouldFailAuthenticationFastWhenCallbackContainsErrorParameters() {
    HttpServletRequest errorRequest = mock(HttpServletRequest.class);
    when(errorRequest.getMethod()).thenReturn("GET");
    when(errorRequest.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(errorRequest.getQueryString()).thenReturn("error=access_denied&error_description=User+denied");

    TestCallbackContext context = new TestCallbackContext(errorRequest);

    assertThatThrownBy(() -> underTest.callback(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Authentication request failed")
        .hasMessageContaining("access_denied");

    assertThat(context.userIdentity).isNull();
    wireMockServer.verify(0, postRequestedFor(urlEqualTo("/protocol/openid-connect/token")));
  }

  private void initializeComponents(String loginStrategy, boolean syncGroups) {
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

  private void stubTokenResponse(String body) {
    wireMockServer.stubFor(post(urlEqualTo("/protocol/openid-connect/token"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(body)));
  }

  private void stubUserInfoResponse(int status, String body) {
    wireMockServer.stubFor(get(urlEqualTo("/protocol/openid-connect/userinfo"))
        .willReturn(aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(body)));
  }

  private void assertSuccessfulAuthentication(TestCallbackContext context, String expectedLogin) {
    assertThat(context.csrfStateVerified.get()).isTrue();
    assertThat(context.redirectedToRequestedPage.get()).isTrue();
    assertThat(context.userIdentity).isNotNull();
    assertThat(context.userIdentity.getProviderId()).isEqualTo(SUBJECT);
    assertThat(context.userIdentity.getProviderLogin()).isEqualTo(expectedLogin);
    assertThat(context.userIdentity.getName()).isEqualTo("John Doo");
    assertThat(context.userIdentity.getEmail()).isEqualTo("john.doo@acme.com");
    assertThat(context.userIdentity.getGroups()).containsExactlyInAnyOrder("group1", "group2");
  }

  private TestCallbackContext createCallbackContext() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(request.getQueryString()).thenReturn("state=" + STATE + "&code=" + VALID_CODE);
    return new TestCallbackContext(request);
  }

  private static String buildIdToken(boolean includeProfile, boolean includeGroups) {
    JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject(SUBJECT)
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

  private static String createTokenResponseBody(String idToken, String accessToken) {
    return String.format(
        "{\"access_token\":\"%s\",\"refresh_token\":\"refresh\",\"scope\":\"openid email profile\",\"id_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":300}",
        accessToken, idToken);
  }

  private static String createUserInfoResponseBody() {
    return String.format(
        "{\"sub\":\"%s\",\"name\":\"John Doo\",\"preferred_username\":\"john.doo\",\"email\":\"john.doo@acme.com\",\"email_verified\":true,\"myGroups\":[\"group1\",\"group2\"]}",
        SUBJECT);
  }

  private static class TestCallbackContext implements OAuth2IdentityProvider.CallbackContext {
    final HttpServletRequest request;
    final AtomicBoolean csrfStateVerified = new AtomicBoolean(false);
    final AtomicBoolean redirectedToRequestedPage = new AtomicBoolean(false);
    UserIdentity userIdentity;

    TestCallbackContext(HttpServletRequest request) {
      this.request = request;
    }

    @Override
    public void verifyCsrfState() {
      csrfStateVerified.set(true);
    }
    @Override
    public void verifyCsrfState(String parameterName) {}
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
      throw new UnsupportedOperationException();
    }
  }

  private static class TestInitContext implements OAuth2IdentityProvider.InitContext {
    String redirectUrl;
    private final String csrfState;

    TestInitContext(String csrfState) {
      this.csrfState = csrfState;
    }

    @Override
    public String generateCsrfState() {
      return csrfState;
    }
    @Override
    public void redirectTo(String url) {
      redirectUrl = url;
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