
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

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.server.authentication.OAuth2IdentityProvider;
import org.sonar.api.server.authentication.UserIdentity;

public class IntegrationTest extends AbstractOidcTest {

  private static final String SUBJECT = "afaba592-ac85-4cf1-8ec6-0058d157f882";
  private static final String LOGIN = "john.doo";
  private static final String NAME = "John Doo";
  private static final String EMAIL = "john.doo@acme.com";
  private static final String PROFILE_ACCESS_TOKEN = "profile-fallback-access-token";
  private static final String GROUPS_ACCESS_TOKEN = "groups-fallback-access-token";

  @Rule
  public WireMockRule idp = new WireMockRule(WireMockConfiguration.wireMockConfig().dynamicPort());

  private String idpUri;
  private OidcClient oidcClient;
  private UserIdentityFactory userIdentityFactory;
  private OidcIdentityProvider underTest;

  @Before
  public void init() {
    idpUri = idp.baseUrl();
    setSettings(true, idpUri);
    oidcClient = createSpyOidcClient();
    userIdentityFactory = new UserIdentityFactory(config);
    underTest = new OidcIdentityProvider(config, oidcClient, userIdentityFactory);
  }

  @After
  public void tearDown() {
    idp.resetAll();
  }

  @Test
  public void shouldRedirectBrowserToOidcAuthenticationForm() throws Exception {
    DumbInitContext context = new DumbInitContext("the-csrf-state");

    underTest.init(context);

    assertThat(context.redirectedTo)
        .startsWith(idpUri + "/protocol/openid-connect/auth")
        .contains("response_type=code")
        .contains("client_id=id")
        .contains("state=the-csrf-state")
        .contains("scope=" + encode("openid email profile", StandardCharsets.UTF_8.name()));
  }

  @Test
  public void shouldAuthenticateUserUsingClaimsFromIdToken() throws Exception {
    stubTokenResponse(newSuccessfulAccessTokenResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, LOGIN);
    idp.verify(1, postRequestedFor(urlEqualTo("/protocol/openid-connect/token"))
        .withHeader("Authorization", matching("Basic .+"))
        .withRequestBody(containing("grant_type=authorization_code"))
        .withRequestBody(containing("code=" + VALID_CODE))
        .withRequestBody(containing("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()))));
    idp.verify(0, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo")));
  }

  @Test
  public void shouldFetchUserInfoWhenIdTokenMissesProfileClaims() {
    stubTokenResponse(newSuccessfulAccessTokenResponseWithoutUserInfo());
    stubUserInfoResponse(newUserInfoResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, LOGIN);
    idp.verify(1, postRequestedFor(urlEqualTo("/protocol/openid-connect/token")));
    idp.verify(1, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", equalTo("Bearer " + PROFILE_ACCESS_TOKEN)));
  }

  @Test
  public void shouldFetchUserInfoWhenIdTokenMissesGroupsClaim() {
    stubTokenResponse(newSuccessfulAccessTokenResponseWithoutGroupsClaim());
    stubUserInfoResponse(newUserInfoResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, LOGIN);
    idp.verify(1, postRequestedFor(urlEqualTo("/protocol/openid-connect/token")));
    idp.verify(1, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", equalTo("Bearer " + GROUPS_ACCESS_TOKEN)));
  }

  @Test
  public void shouldApplyUniqueLoginStrategyConfiguredInOidcConfiguration() {
    settings.setProperty(OidcConfiguration.LOGIN_STRATEGY, OidcConfiguration.LOGIN_STRATEGY_UNIQUE);
    userIdentityFactory = new UserIdentityFactory(config);
    underTest = new OidcIdentityProvider(config, oidcClient, userIdentityFactory);
    stubTokenResponse(newSuccessfulAccessTokenResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, SUBJECT + "@" + OidcIdentityProvider.KEY);
    idp.verify(1, postRequestedFor(urlEqualTo("/protocol/openid-connect/token")));
    idp.verify(0, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo")));
  }

  @Test
  public void shouldRejectInitWhenOidcIsDisabled() {
    setSettings(false);
    userIdentityFactory = new UserIdentityFactory(config);
    underTest = new OidcIdentityProvider(config, oidcClient, userIdentityFactory);
    DumbInitContext context = new DumbInitContext("the-csrf-state");

    try {
      underTest.init(context);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("OpenID Connect authentication is disabled", e.getMessage());
    }

    assertThat(context.redirectedTo).isNull();
  }

  @Test
  public void shouldFailWhenTokenEndpointReturnsError() {
    idp.stubFor(post(urlEqualTo("/protocol/openid-connect/token"))
        .willReturn(aResponse()
            .withStatus(500)
            .withHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
            .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid resource owner credentials\"}")));
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("Token request failed: {\"error_description\":\"Invalid resource owner credentials\","
          + "\"error\":\"invalid_grant\"}", e.getMessage());
    }

    assertFailedAuthentication(callbackContext);
    idp.verify(1, postRequestedFor(urlEqualTo("/protocol/openid-connect/token")));
    idp.verify(0, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo")));
  }

  @Test
  public void shouldFailWhenUserInfoEndpointReturnsError() {
    stubTokenResponse(newSuccessfulAccessTokenResponseWithoutUserInfo());
    idp.stubFor(get(urlEqualTo("/protocol/openid-connect/userinfo"))
        .willReturn(aResponse()
            .withStatus(401)
            .withHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
            .withBody("{\"error\":\"invalid_token\"}")));
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("UserInfo request failed: {\"error\":\"invalid_token\"}", e.getMessage());
    }

    assertFailedAuthentication(callbackContext);
    idp.verify(1, postRequestedFor(urlEqualTo("/protocol/openid-connect/token")));
    idp.verify(1, getRequestedFor(urlEqualTo("/protocol/openid-connect/userinfo"))
        .withHeader("Authorization", equalTo("Bearer " + PROFILE_ACCESS_TOKEN)));
  }

  @Test
  public void shouldFailFastWhenCallbackContainsAuthenticationError() {
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationErrorRequest());

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).startsWith("Authentication request failed:");
    }

    assertFailedAuthentication(callbackContext);
    idp.verify(0, postRequestedFor(urlEqualTo("/protocol/openid-connect/token")));
  }

  private void stubTokenResponse(String body) {
    idp.stubFor(post(urlEqualTo("/protocol/openid-connect/token"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
            .withBody(body)));
  }

  private void stubUserInfoResponse(String body) {
    idp.stubFor(get(urlEqualTo("/protocol/openid-connect/userinfo"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
            .withBody(body)));
  }

  private static void assertAuthenticated(DumbCallbackContext callbackContext, String expectedProviderLogin) {
    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    assertThat(callbackContext.userIdentity).isNotNull();
    assertThat(callbackContext.userIdentity.getProviderId()).isEqualTo(SUBJECT);
    assertThat(callbackContext.userIdentity.getProviderLogin()).isEqualTo(expectedProviderLogin);
    assertThat(callbackContext.userIdentity.getName()).isEqualTo(NAME);
    assertThat(callbackContext.userIdentity.getEmail()).isEqualTo(EMAIL);
    assertThat(callbackContext.userIdentity.getGroups()).containsExactlyInAnyOrder("group1", "group2");
  }

  private static void assertFailedAuthentication(DumbCallbackContext callbackContext) {
    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();
    assertThat(callbackContext.userIdentity).isNull();
  }

  private static HttpServletRequest newAuthenticationRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(request.getQueryString()).thenReturn("state=" + STATE + "&code=" + VALID_CODE);
    return request;
  }

  private static HttpServletRequest newAuthenticationErrorRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(request.getQueryString())
        .thenReturn("error=invalid_request&error_description=the request is not valid or malformed");
    return request;
  }

  private static String newSuccessfulAccessTokenResponse() {
    return oidcTokenResponse("id-token-access-token", buildIdToken(true, true));
  }

  private static String newSuccessfulAccessTokenResponseWithoutUserInfo() {
    return oidcTokenResponse(PROFILE_ACCESS_TOKEN, buildIdToken(false, true));
  }

  private static String newSuccessfulAccessTokenResponseWithoutGroupsClaim() {
    return oidcTokenResponse(GROUPS_ACCESS_TOKEN, buildIdToken(true, false));
  }

  private static String newUserInfoResponse() {
    return "{\"sub\":\"" + SUBJECT + "\","
        + "\"name\":\"" + NAME + "\","
        + "\"preferred_username\":\"" + LOGIN + "\","
        + "\"email\":\"" + EMAIL + "\","
        + "\"email_verified\":true,"
        + "\"myGroups\":[\"group1\",\"group2\"]}";
  }

  private static String oidcTokenResponse(String accessToken, String idToken) {
    return "{\"access_token\":\"" + accessToken + "\","
        + "\"refresh_token\":\"refresh-token\","
        + "\"scope\":\"openid email profile\","
        + "\"id_token\":\"" + idToken + "\","
        + "\"token_type\":\"Bearer\","
        + "\"expires_in\":300}";
  }

  private static String buildIdToken(boolean includeProfileClaims, boolean includeGroupsClaim) {
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .subject(SUBJECT)
        .claim("email", EMAIL);

    if (includeProfileClaims) {
      claims.claim("name", NAME)
          .claim("preferred_username", LOGIN)
          .claim("given_name", "John")
          .claim("family_name", "Doo");
    }
    if (includeGroupsClaim) {
      claims.claim("myGroups", Arrays.asList("group1", "group2"));
    }

    return new PlainJWT(claims.build()).serialize();
  }

  private static class DumbCallbackContext implements OAuth2IdentityProvider.CallbackContext {
    final HttpServletRequest request;
    final AtomicBoolean csrfStateVerified = new AtomicBoolean(false);
    final AtomicBoolean redirectedToRequestedPage = new AtomicBoolean(false);
    UserIdentity userIdentity;

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

    @Override
    public void verifyCsrfState(String parameterName) {
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
