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

import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.server.authentication.OAuth2IdentityProvider;
import org.sonar.api.server.authentication.UserIdentity;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class IntegrationTest extends AbstractOidcTest {

  private static final String SUBJECT = "afaba592-ac85-4cf1-8ec6-0058d157f882";
  private static final String LOGIN = "john.doo";
  private static final String NAME = "John Doo";
  private static final String EMAIL = "john.doo@acme.com";
  private static final String PROFILE_ACCESS_TOKEN = "profile-fallback-access-token";
  private static final String GROUPS_ACCESS_TOKEN = "groups-fallback-access-token";

  @Rule
  public MockWebServer idp = new MockWebServer();

  private String idpUri;
  private OidcClient oidcClient;
  private UserIdentityFactory userIdentityFactory;
  private OidcIdentityProvider underTest;

  @Before
  public void init() {
    idpUri = idp.url("/").toString();
    idpUri = idpUri.substring(0, idpUri.length() - 1);
    setSettings(true, idpUri);
    oidcClient = createSpyOidcClient();
    userIdentityFactory = new UserIdentityFactory(config);
    underTest = new OidcIdentityProvider(config, oidcClient, userIdentityFactory);
  }

  @Test
  public void shouldRedirectBrowserToOidcAuthenticationForm() throws Exception {
    DumbInitContext context = new DumbInitContext("the-csrf-state");

    underTest.init(context);

    assertThat(context.redirectedTo)
        .startsWith(idp.url("/protocol/openid-connect/auth").toString())
        .contains("response_type=code")
        .contains("client_id=id")
        .contains("state=the-csrf-state")
        .contains("scope=" + encode("openid email profile", StandardCharsets.UTF_8.name()));
  }

  @Test
  public void shouldAuthenticateUserUsingClaimsFromIdToken() throws Exception {
    idp.enqueue(newSuccessfulAccessTokenResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, LOGIN);
    assertAccessTokenRequest(idp.takeRequest());
  }

  @Test
  public void shouldFetchUserInfoWhenIdTokenMissesProfileClaims() throws Exception {
    idp.enqueue(newSuccessfulAccessTokenResponseWithoutUserInfo());
    idp.enqueue(newUserInfoResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, LOGIN);
    assertAccessTokenRequest(idp.takeRequest());
    assertUserInfoRequest(idp.takeRequest(), PROFILE_ACCESS_TOKEN);
  }

  @Test
  public void shouldFetchUserInfoWhenIdTokenMissesGroupsClaim() throws Exception {
    idp.enqueue(newSuccessfulAccessTokenResponseWithoutGroupsClaim());
    idp.enqueue(newUserInfoResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, LOGIN);
    assertAccessTokenRequest(idp.takeRequest());
    assertUserInfoRequest(idp.takeRequest(), GROUPS_ACCESS_TOKEN);
  }

  @Test
  public void shouldUseUniqueLoginStrategyWhenConfigured() throws Exception {
    settings.setProperty(OidcConfiguration.LOGIN_STRATEGY, OidcConfiguration.LOGIN_STRATEGY_UNIQUE);
    userIdentityFactory = new UserIdentityFactory(config);
    underTest = new OidcIdentityProvider(config, oidcClient, userIdentityFactory);
    idp.enqueue(newSuccessfulAccessTokenResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticated(callbackContext, SUBJECT + "@" + OidcIdentityProvider.KEY);
    assertAccessTokenRequest(idp.takeRequest());
  }

  @Test
  public void shouldFailWhenTokenEndpointReturnsError() throws Exception {
    idp.enqueue(errorJsonResponse(500,
        "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid resource owner credentials\"}"));
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("Token request failed: {\"error_description\":\"Invalid resource owner credentials\","
          + "\"error\":\"invalid_grant\"}", e.getMessage());
    }

    assertFailedAuthentication(callbackContext);
    assertThat(idp.takeRequest().getPath()).startsWith("/protocol/openid-connect/token");
  }

  @Test
  public void shouldFailWhenUserInfoEndpointReturnsErrorDuringFallback() throws Exception {
    idp.enqueue(newSuccessfulAccessTokenResponseWithoutUserInfo());
    idp.enqueue(errorJsonResponse(401, "{\"error\":\"invalid_token\"}"));
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("UserInfo request failed: {\"error\":\"invalid_token\"}", e.getMessage());
    }

    assertFailedAuthentication(callbackContext);
    assertAccessTokenRequest(idp.takeRequest());
    assertUserInfoRequest(idp.takeRequest(), PROFILE_ACCESS_TOKEN);
  }

  @Test
  public void shouldFailWhenCallbackContainsAuthenticationError() {
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationErrorRequest());

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertEquals("Authentication request failed: {\"error_description\":"
          + "\"the request is not valid or malformed\",\"error\":\"invalid_request\"}", e.getMessage());
    }

    assertFailedAuthentication(callbackContext);
    assertThat(idp.getRequestCount()).isZero();
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

  private static void assertAccessTokenRequest(RecordedRequest request) throws Exception {
    assertThat(request.getPath()).startsWith("/protocol/openid-connect/token");
    assertThat(request.getHeader("Authorization")).startsWith("Basic ");
    String body = request.getBody().readUtf8();
    assertThat(body).contains("grant_type=authorization_code");
    assertThat(body).contains("code=" + VALID_CODE);
    assertThat(body).contains("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()));
  }

  private static void assertUserInfoRequest(RecordedRequest request, String accessToken) {
    assertThat(request.getPath()).isEqualTo("/protocol/openid-connect/userinfo");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + accessToken);
  }

  private static HttpServletRequest newAuthenticationRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(request.getQueryString()).thenReturn("state=" + STATE + "&code=" + VALID_CODE);
    when(request.getRequestURL()).thenReturn(new StringBuffer(CALLBACK_URL));
    when(request.getRequestURI()).thenReturn("/callback");
    when(request.getScheme()).thenReturn("http");
    when(request.getServerName()).thenReturn("localhost");
    when(request.getServerPort()).thenReturn(80);
    return request;
  }

  private static HttpServletRequest newAuthenticationErrorRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(request.getQueryString())
        .thenReturn("error=invalid_request&error_description=the request is not valid or malformed");
    when(request.getRequestURL()).thenReturn(new StringBuffer(CALLBACK_URL));
    when(request.getRequestURI()).thenReturn("/callback");
    when(request.getScheme()).thenReturn("http");
    when(request.getServerName()).thenReturn("localhost");
    when(request.getServerPort()).thenReturn(80);
    return request;
  }

  private static MockResponse newSuccessfulAccessTokenResponse() {
    return jsonResponse(tokenResponse("id-token-access-token", buildIdToken(true, true)));
  }

  private static MockResponse newSuccessfulAccessTokenResponseWithoutUserInfo() {
    return jsonResponse(tokenResponse(PROFILE_ACCESS_TOKEN, buildIdToken(false, true)));
  }

  private static MockResponse newSuccessfulAccessTokenResponseWithoutGroupsClaim() {
    return jsonResponse(tokenResponse(GROUPS_ACCESS_TOKEN, buildIdToken(true, false)));
  }

  private static MockResponse newUserInfoResponse() {
    return jsonResponse("{\"sub\":\"" + SUBJECT + "\","
        + "\"name\":\"" + NAME + "\","
        + "\"preferred_username\":\"" + LOGIN + "\","
        + "\"email\":\"" + EMAIL + "\","
        + "\"email_verified\":true,"
        + "\"myGroups\":[\"group1\",\"group2\"]}");
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse()
        .addHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
        .setBody(body);
  }

  private static MockResponse errorJsonResponse(int responseCode, String body) {
    return new MockResponse()
        .addHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
        .setResponseCode(responseCode)
        .setBody(body);
  }

  private static String tokenResponse(String accessToken, String idToken) {
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