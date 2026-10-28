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

import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.nimbusds.common.contenttype.ContentType;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.server.authentication.OAuth2IdentityProvider;
import org.sonar.api.server.authentication.UserIdentity;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class IntegrationTest extends AbstractOidcTest {

  private static final String SUBJECT = "8f63a486-6699-4f25-beef-118dd240bef8";
  private static final String LOGIN = "john.doo";
  private static final String NAME = "John Doo";
  private static final String EMAIL = "john.doo@acme.com";

  @Rule
  public MockWebServer idp = new MockWebServer();

  private String idpUri;
  private OidcClient oidcClient;
  private UserIdentityFactory userIdentityFactory;
  private OidcIdentityProvider underTest;

  @Before
  public void init() {
    idpUri = format("http://%s:%d", idp.getHostName(), idp.getPort());
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
        .startsWith(idpUri + "/protocol/openid-connect/auth")
        .contains("scope=" + encode("openid email profile", StandardCharsets.UTF_8.name()));
  }

  @Test
  public void shouldIncludeStateAndClientIdInAuthenticationRedirect() {
    DumbInitContext context = new DumbInitContext("the-csrf-state");

    underTest.init(context);

    assertThat(context.redirectedTo)
        .contains("state=the-csrf-state")
        .contains("client_id=id")
        .contains("response_type=code");
  }

  @Test
  public void shouldRejectInitWhenOidcIsDisabled() {
    setSettings(false);
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
  public void shouldAuthenticateUserUsingClaimsFromIdToken() throws InterruptedException {
    idp.enqueue(newSuccessfulAccessTokenResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticatedUser(callbackContext);
    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

    RecordedRequest accessTokenRequest = idp.takeRequest();
    assertThat(accessTokenRequest.getMethod()).isEqualTo("POST");
    assertThat(accessTokenRequest.getPath()).startsWith("/protocol/openid-connect/token");
    assertThat(accessTokenRequest.getHeader("Authorization")).startsWith("Basic ");
    assertThat(accessTokenRequest.getBody().readUtf8())
        .contains("grant_type=authorization_code")
        .contains("code=" + VALID_CODE);

    assertThat(idp.getRequestCount()).isEqualTo(1);
  }

  @Test
  public void shouldRequestUserInfoWhenIdTokenMissesProfileClaims() throws InterruptedException {
    idp.enqueue(newSuccessfulAccessTokenResponseWithoutUserInfo());
    idp.enqueue(newUserInfoResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticatedUser(callbackContext);
    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

    RecordedRequest accessTokenRequest = idp.takeRequest();
    assertThat(accessTokenRequest.getMethod()).isEqualTo("POST");
    assertThat(accessTokenRequest.getPath()).startsWith("/protocol/openid-connect/token");

    RecordedRequest userInfoRequest = idp.takeRequest();
    assertThat(userInfoRequest.getMethod()).isEqualTo("GET");
    assertThat(userInfoRequest.getPath()).startsWith("/protocol/openid-connect/userinfo");
    assertThat(userInfoRequest.getHeader("Authorization")).isEqualTo("Bearer access-token");

    assertThat(idp.getRequestCount()).isEqualTo(2);
  }

  @Test
  public void shouldRequestUserInfoWhenIdTokenMissesGroupsClaim() throws InterruptedException {
    idp.enqueue(newSuccessfulAccessTokenResponseWithoutGroupsClaim());
    idp.enqueue(newUserInfoResponse());
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    underTest.callback(callbackContext);

    assertAuthenticatedUser(callbackContext);
    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

    RecordedRequest accessTokenRequest = idp.takeRequest();
    assertThat(accessTokenRequest.getMethod()).isEqualTo("POST");
    assertThat(accessTokenRequest.getPath()).startsWith("/protocol/openid-connect/token");

    RecordedRequest userInfoRequest = idp.takeRequest();
    assertThat(userInfoRequest.getMethod()).isEqualTo("GET");
    assertThat(userInfoRequest.getPath()).startsWith("/protocol/openid-connect/userinfo");
    assertThat(userInfoRequest.getHeader("Authorization")).isEqualTo("Bearer access-token");

    assertThat(idp.getRequestCount()).isEqualTo(2);
  }

  @Test
  public void shouldFailWhenTokenEndpointReturnsError() throws InterruptedException {
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

    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.userIdentity).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();

    RecordedRequest accessTokenRequest = idp.takeRequest();
    assertThat(accessTokenRequest.getMethod()).isEqualTo("POST");
    assertThat(accessTokenRequest.getPath()).startsWith("/protocol/openid-connect/token");
    assertThat(idp.getRequestCount()).isEqualTo(1);
  }

  @Test
  public void shouldFailWhenUserInfoEndpointReturnsError() throws InterruptedException {
    idp.enqueue(newSuccessfulAccessTokenResponseWithoutUserInfo());
    idp.enqueue(errorJsonResponse(500,
        "{\"error\":\"invalid_token\",\"error_description\":\"The access token is invalid\"}"));
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationRequest());

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).startsWith("UserInfo request failed:");
    }

    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.userIdentity).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();

    RecordedRequest accessTokenRequest = idp.takeRequest();
    assertThat(accessTokenRequest.getMethod()).isEqualTo("POST");
    assertThat(accessTokenRequest.getPath()).startsWith("/protocol/openid-connect/token");

    RecordedRequest userInfoRequest = idp.takeRequest();
    assertThat(userInfoRequest.getMethod()).isEqualTo("GET");
    assertThat(userInfoRequest.getPath()).startsWith("/protocol/openid-connect/userinfo");
    assertThat(userInfoRequest.getHeader("Authorization")).isEqualTo("Bearer access-token");

    assertThat(idp.getRequestCount()).isEqualTo(2);
  }

  @Test
  public void shouldFailWhenAuthorizationResponseContainsError() throws Exception {
    DumbCallbackContext callbackContext = new DumbCallbackContext(newAuthenticationErrorRequest());

    try {
      underTest.callback(callbackContext);
      failBecauseExceptionWasNotThrown(IllegalStateException.class);
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).startsWith("Authentication request failed:");
    }

    assertThat(callbackContext.csrfStateVerified.get()).isTrue();
    assertThat(callbackContext.userIdentity).isNull();
    assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();
    assertThat(idp.getRequestCount()).isEqualTo(0);
  }

  private static void assertAuthenticatedUser(DumbCallbackContext callbackContext) {
    assertThat(callbackContext.userIdentity).isNotNull();
    assertThat(callbackContext.userIdentity.getProviderLogin()).isEqualTo(LOGIN);
    assertThat(callbackContext.userIdentity.getName()).isEqualTo(NAME);
    assertThat(callbackContext.userIdentity.getEmail()).isEqualTo(EMAIL);
    assertThat(callbackContext.userIdentity.getGroups()).containsExactlyInAnyOrder("group1", "group2");
  }

  private static HttpServletRequest newAuthenticationRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(request.getQueryString()).thenReturn("state=" + STATE + "&code=" + VALID_CODE);
    when(request.getRequestURL()).thenReturn(new StringBuffer(CALLBACK_URL));
    return request;
  }

  private static HttpServletRequest newAuthenticationErrorRequest() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(request.getQueryString()).thenReturn("state=" + STATE + "&error=access_denied&error_description="
        + encode("User denied access", StandardCharsets.UTF_8.name()));
    when(request.getRequestURL()).thenReturn(new StringBuffer(CALLBACK_URL));
    return request;
  }

  private static MockResponse newSuccessfulAccessTokenResponse() {
    return jsonResponse(tokenResponse(jwt("{"
        + "\"sub\":\"" + SUBJECT + "\","
        + "\"name\":\"" + NAME + "\","
        + "\"preferred_username\":\"" + LOGIN + "\","
        + "\"email\":\"" + EMAIL + "\","
        + "\"myGroups\":[\"group1\",\"group2\"]"
        + "}")));
  }

  private static MockResponse newSuccessfulAccessTokenResponseWithoutGroupsClaim() {
    return jsonResponse(tokenResponse(jwt("{"
        + "\"sub\":\"" + SUBJECT + "\","
        + "\"name\":\"" + NAME + "\","
        + "\"preferred_username\":\"" + LOGIN + "\","
        + "\"email\":\"" + EMAIL + "\""
        + "}")));
  }

  private static MockResponse newSuccessfulAccessTokenResponseWithoutUserInfo() {
    return jsonResponse(tokenResponse(jwt("{\"sub\":\"" + SUBJECT + "\"}")));
  }

  private static MockResponse newUserInfoResponse() {
    return jsonResponse("{"
        + "\"sub\":\"" + SUBJECT + "\","
        + "\"name\":\"" + NAME + "\","
        + "\"preferred_username\":\"" + LOGIN + "\","
        + "\"email\":\"" + EMAIL + "\","
        + "\"email_verified\":true,"
        + "\"myGroups\":[\"group1\",\"group2\"]"
        + "}");
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse()
        .setHeader("content-type", ContentType.APPLICATION_JSON.toString())
        .setBody(body);
  }

  private static MockResponse errorJsonResponse(int status, String body) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("content-type", ContentType.APPLICATION_JSON.toString())
        .setBody(body);
  }

  private static String tokenResponse(String idToken) {
    return "{"
        + "\"access_token\":\"access-token\","
        + "\"refresh_token\":\"refresh-token\","
        + "\"scope\":\"openid email profile\","
        + "\"id_token\":\"" + idToken + "\","
        + "\"token_type\":\"Bearer\","
        + "\"expires_in\":300"
        + "}";
  }

  private static String jwt(String payloadJson) {
    return encodeBase64Url("{\"typ\":\"JWT\",\"alg\":\"RS256\"}")
        + "."
        + encodeBase64Url(payloadJson)
        + "."
        + encodeBase64Url("signature");
  }

  private static String encodeBase64Url(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
      this.csrfStateVerified.set(true);
    }

    @Override
    public void redirectToRequestedPage() {
      this.redirectedToRequestedPage.set(true);
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