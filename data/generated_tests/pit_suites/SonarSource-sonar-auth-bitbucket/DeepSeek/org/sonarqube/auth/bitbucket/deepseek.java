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

import com.github.scribejava.core.exceptions.OAuthException;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.config.PropertyDefinitions;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.server.authentication.OAuth2IdentityProvider;
import org.sonar.api.server.authentication.UnauthorizedException;
import org.sonar.api.server.authentication.UserIdentity;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class deepseek {

    private static final String CALLBACK_URL = "http://localhost/oauth/callback/bitbucket";
    private static final String CLIENT_ID = "the_id";
    private static final String CLIENT_SECRET = "the_secret";

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());

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
        settings.setProperty("sonar.auth.bitbucket.clientId.secured", CLIENT_ID);
        settings.setProperty("sonar.auth.bitbucket.clientSecret.secured", CLIENT_SECRET);
        settings.setProperty("sonar.auth.bitbucket.enabled", true);
        settings.setProperty("sonar.auth.bitbucket.apiUrl", format("http://localhost:%d/", wireMockRule.port()));
        settings.setProperty("sonar.auth.bitbucket.webUrl", format("http://localhost:%d/", wireMockRule.port()));

        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        scribeApi = new BitbucketScribeApi(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);
    }

    @After
    public void tearDown() {
        wireMockRule.resetAll();
    }

    @Test
    public void redirect_browser_to_bitbucket_authentication_form() throws Exception {
        DumbInitContext context = new DumbInitContext("the-csrf-state");

        underTest.init(context);

        String expectedScope = encode("account", StandardCharsets.UTF_8.name());
        assertThat(context.redirectedTo)
                .startsWith(format("http://localhost:%d/site/oauth2/authorize", wireMockRule.port()))
                .contains("scope=" + expectedScope);
    }

    @Test
    public void authenticate_successfully() {
        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubPrimaryEmailRequest("john@bitbucket.org");

        HttpServletRequest request = mockRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);

        underTest.callback(callbackContext);

        assertThat(callbackContext.csrfStateVerified.get()).isFalse();
        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
        assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

        verify(1, postRequestedFor(urlPathEqualTo("/site/oauth2/access_token")));
        verify(getRequestedFor(urlPathEqualTo("/2.0/user")));
        verify(getRequestedFor(urlPathEqualTo("/2.0/user/emails")));
        verify(0, getRequestedFor(urlPathEqualTo("/2.0/teams")));
    }

    @Test
    public void authenticate_with_provider_login_strategy() {
        settings.setProperty("sonar.auth.bitbucket.loginStrategy", BitbucketSettings.LOGIN_STRATEGY_PROVIDER_LOGIN);

        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubPrimaryEmailRequest("john@bitbucket.org");

        HttpServletRequest request = mockRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);

        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
        assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
    }

    @Test
    public void authenticate_without_email_should_not_set_email() {
        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubNoEmailResponse();

        HttpServletRequest request = mockRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);

        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
        assertThat(callbackContext.userIdentity.getEmail()).isNull();
    }

    @Test
    public void callback_throws_OAuthException_if_error_when_requesting_access_token() {
        stubAccessTokenError();

        expectedException.expect(OAuthException.class);
        expectedException.expectMessage("Cannot extract an access token");

        DumbCallbackContext context = new DumbCallbackContext(mockRequest("code"));

        underTest.callback(context);
    }

    @Test
    public void callback_throws_ISE_if_error_when_requesting_user_profile() {
        stubAccessTokenRequest();
        stubUserError();

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("Can not get Bitbucket user profile. HTTP code: 500");

        DumbCallbackContext context = new DumbCallbackContext(mockRequest("code"));

        underTest.callback(context);
    }

    @Test
    public void callback_handles_missing_email_response_gracefully() {
        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubEmailError();

        DumbCallbackContext context = new DumbCallbackContext(mockRequest("code"));

        underTest.callback(context);

        assertThat(context.userIdentity.getEmail()).isNull();
        assertThat(context.redirectedToRequestedPage.get()).isTrue();
    }

    @Test
    public void allow_authentication_if_user_is_member_of_one_restricted_team() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[]{"team1", "team2"});

        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubPrimaryEmailRequest("john@bitbucket.org");
        stubTeamsResponse("team3", "team2");

        DumbCallbackContext context = new DumbCallbackContext(mockRequest("code"));

        underTest.callback(context);

        assertThat(context.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(context.redirectedToRequestedPage.get()).isTrue();
        verify(getRequestedFor(urlPathEqualTo("/2.0/teams")));
    }

    @Test
    public void forbid_authentication_if_user_is_not_member_of_restricted_team() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[]{"team1", "team2"});

        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubPrimaryEmailRequest("john@bitbucket.org");
        stubTeamsResponse("team3");

        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage("User john is not part of restricted teams");

        underTest.callback(new DumbCallbackContext(mockRequest("code")));
    }

    @Test
    public void forbid_authentication_if_user_is_not_member_of_any_team() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[]{"team1", "team2"});

        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubPrimaryEmailRequest("john@bitbucket.org");
        stubEmptyTeamsResponse();

        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage("User john is not part of restricted teams");

        underTest.callback(new DumbCallbackContext(mockRequest("code")));
    }

    @Test
    public void allow_authentication_if_team_restriction_is_empty() {
        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubPrimaryEmailRequest("john@bitbucket.org");

        DumbCallbackContext context = new DumbCallbackContext(mockRequest("code"));

        underTest.callback(context);

        assertThat(context.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(context.redirectedToRequestedPage.get()).isTrue();
        verify(0, getRequestedFor(urlPathEqualTo("/2.0/teams")));
    }

    @Test
    public void forbid_authentication_if_teams_request_fails() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[]{"team1"});

        stubAccessTokenRequest();
        stubUserRequest("john", "John");
        stubPrimaryEmailRequest("john@bitbucket.org");
        stubTeamsError();

        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage("User john is not part of restricted teams");

        underTest.callback(new DumbCallbackContext(mockRequest("code")));
    }

    private void stubAccessTokenRequest() {
        stubFor(post(urlPathEqualTo("/site/oauth2/access_token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"e72e16c7e42f292c6912e7710c838347ae178b4a\",\"scope\":\"user\"}")));
    }

    private void stubAccessTokenError() {
        stubFor(post(urlPathEqualTo("/site/oauth2/access_token"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("error")));
    }

    private void stubUserRequest(String login, String name) {
        stubFor(get(urlPathEqualTo("/2.0/user"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"username\":\"" + login + "\",\"display_name\":\"" + name + "\"}")));
    }

    private void stubUserError() {
        stubFor(get(urlPathEqualTo("/2.0/user"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\":\"failure\"}")));
    }

    private void stubPrimaryEmailRequest(String email) {
        stubFor(get(urlPathEqualTo("/2.0/user/emails"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[{\"active\":true,\"email\":\"" + email + "\",\"is_primary\":true}]}")));
    }

    private void stubNoEmailResponse() {
        stubFor(get(urlPathEqualTo("/2.0/user/emails"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[]}")));
    }

    private void stubEmailError() {
        stubFor(get(urlPathEqualTo("/2.0/user/emails"))
                .willReturn(aResponse().withStatus(500)));
    }

    private void stubTeamsResponse(String... teams) {
        StringBuilder values = new StringBuilder();
        for (String team : teams) {
            if (values.length() > 0) {
                values.append(",");
            }
            values.append("{\"username\":\"").append(team).append("\"}");
        }

        stubFor(get(urlPathEqualTo("/2.0/teams"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[" + values + "]}")));
    }

    private void stubEmptyTeamsResponse() {
        stubTeamsResponse();
    }

    private void stubTeamsError() {
        stubFor(get(urlPathEqualTo("/2.0/teams"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("error")));
    }

    private static HttpServletRequest mockRequest(String verifierCode) {
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
