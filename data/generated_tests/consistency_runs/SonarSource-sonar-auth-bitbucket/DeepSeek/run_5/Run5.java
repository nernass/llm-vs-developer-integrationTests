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

import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Token;
import com.github.scribejava.core.oauth.OAuthService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BitbucketIdentityProviderIntegrationTest {

    private static final String CALLBACK_URL = "http://localhost/oauth/callback/bitbucket";
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";

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
    public void setUp() throws IOException {
        settings = new MapSettings(new PropertyDefinitions(BitbucketSettings.definitions()));
        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        scribeApi = new BitbucketScribeApi(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

        String baseUrl = format("http://%s:%d", bitbucket.getHostName(), bitbucket.getPort());
        settings.setProperty(BitbucketSettings.ENABLED, true);
        settings.setProperty(BitbucketSettings.CONSUMER_KEY, CLIENT_ID);
        settings.setProperty(BitbucketSettings.CONSUMER_SECRET, CLIENT_SECRET);
        settings.setProperty(BitbucketSettings.ALLOW_USERS_TO_SIGN_UP, true);
        settings.setProperty(BitbucketSettings.API_URL, baseUrl);
        settings.setProperty(BitbucketSettings.WEB_URL, baseUrl);
    }

    @Test
    public void redirect_browser_to_bitbucket_authentication_form() throws Exception {
        DumbInitContext context = new DumbInitContext("the-csrf-state");
        underTest.init(context);

        assertThat(context.redirectedTo)
                .startsWith(bitbucket.url("site/oauth2/authorize").toString())
                .contains("scope=" + encode(BitbucketIdentityProvider.REQUIRED_SCOPE, StandardCharsets.UTF_8.name()))
                .contains("client_id=" + CLIENT_ID)
                .contains("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()))
                .contains("response_type=code");
    }

    @Test
    public void authenticate_successfully_with_unique_login_strategy() throws Exception {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", "John Doe"));
        bitbucket.enqueue(newPrimaryEmailResponse("john.doe@bitbucket.org"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.csrfStateVerified.get()).isFalse();
        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john.doe@bitbucket");
        assertThat(callbackContext.userIdentity.getProviderLogin()).isEqualTo("john.doe");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("John Doe");
        assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john.doe@bitbucket.org");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

        RecordedRequest accessTokenRequest = bitbucket.takeRequest();
        assertThat(accessTokenRequest.getPath()).startsWith("/site/oauth2/access_token");
        assertThat(accessTokenRequest.getMethod()).isEqualTo("POST");

        RecordedRequest userRequest = bitbucket.takeRequest();
        assertThat(userRequest.getPath()).startsWith("/2.0/user");
        assertThat(userRequest.getMethod()).isEqualTo("GET");

        RecordedRequest emailRequest = bitbucket.takeRequest();
        assertThat(emailRequest.getPath()).startsWith("/2.0/user/emails");
        assertThat(emailRequest.getMethod()).isEqualTo("GET");

        assertThat(bitbucket.getRequestCount()).isEqualTo(3);
    }

    @Test
    public void authenticate_successfully_with_provider_login_strategy() throws Exception {
        settings.setProperty(BitbucketSettings.LOGIN_STRATEGY, BitbucketSettings.LOGIN_STRATEGY_PROVIDER_LOGIN);

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("jane.smith", "Jane Smith"));
        bitbucket.enqueue(newPrimaryEmailResponse("jane.smith@bitbucket.org"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("jane.smith");
        assertThat(callbackContext.userIdentity.getProviderLogin()).isEqualTo("jane.smith");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("Jane Smith");
        assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("jane.smith@bitbucket.org");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    }

    @Test
    public void authenticate_successfully_when_display_name_is_null() throws Exception {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", null));
        bitbucket.enqueue(newPrimaryEmailResponse("john.doe@bitbucket.org"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john.doe@bitbucket");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("john.doe");
        assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john.doe@bitbucket.org");
    }

    @Test
    public void authenticate_successfully_without_primary_email() throws Exception {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", "John Doe"));
        bitbucket.enqueue(newNoPrimaryEmailResponse());

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john.doe@bitbucket");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("John Doe");
        assertThat(callbackContext.userIdentity.getEmail()).isNull();
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    }

    @Test
    public void callback_throws_ISE_if_error_when_requesting_access_token() {
        bitbucket.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}"));

        DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("invalid-code"));
        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("Can not get access token");

        underTest.callback(callbackContext);
    }

    @Test
    public void callback_throws_ISE_if_error_when_requesting_user_profile() {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"Internal Server Error\"}"));

        DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));
        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("Can not get Bitbucket user profile. HTTP code: 500");

        underTest.callback(callbackContext);

        assertThat(callbackContext.csrfStateVerified.get()).isTrue();
        assertThat(callbackContext.userIdentity).isNull();
        assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();
    }

    @Test
    public void callback_throws_ISE_if_error_when_requesting_user_emails() {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", "John Doe"));
        bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"Internal Server Error\"}"));

        DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));
        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("Can not get Bitbucket user emails. HTTP code: 500");

        underTest.callback(callbackContext);
    }

    @Test
    public void allow_authentication_if_user_is_member_of_one_restricted_team() {
        settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[]{"team1", "team2"});

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", "John Doe"));
        bitbucket.enqueue(newPrimaryEmailResponse("john.doe@bitbucket.org"));
        bitbucket.enqueue(newTeamsResponse("team3", "team2", "team4"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john.doe@bitbucket");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

        RecordedRequest teamsRequest = bitbucket.takeRequest();
        assertThat(teamsRequest.getPath()).startsWith("/2.0/teams?role=member");
        assertThat(teamsRequest.getMethod()).isEqualTo("GET");
    }

    @Test
    public void forbid_authentication_if_user_is_not_member_of_one_restricted_team() {
        settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[]{"team1", "team2"});

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", "John Doe"));
        bitbucket.enqueue(newPrimaryEmailResponse("john.doe@bitbucket.org"));
        bitbucket.enqueue(newTeamsResponse("team3", "team4"));

        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage("User john.doe is not part of restricted teams");

        DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
        underTest.callback(context);
    }

    @Test
    public void forbid_authentication_if_user_is_not_member_of_any_team() {
        settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[]{"team1", "team2"});

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", "John Doe"));
        bitbucket.enqueue(newPrimaryEmailResponse("john.doe@bitbucket.org"));
        bitbucket.enqueue(newTeamsResponse());

        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage("User john.doe is not part of restricted teams");

        DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
        underTest.callback(context);
    }

    @Test
    public void forbid_authentication_if_team_restriction_check_fails() {
        settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[]{"team1", "team2"});

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", "John Doe"));
        bitbucket.enqueue(newPrimaryEmailResponse("john.doe@bitbucket.org"));
        bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"Internal Server Error\"}"));

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("Can not get Bitbucket user teams. HTTP code: 500");

        DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
        underTest.callback(context);
    }

    @Test
    public void init_throws_IllegalStateException_when_disabled() {
        settings.setProperty(BitbucketSettings.ENABLED, false);

        DumbInitContext context = new DumbInitContext("test-state");
        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("Bitbucket authentication is disabled");

        underTest.init(context);
    }

    @Test
    public void callback_uses_correct_api_url_when_api_url_configured() throws Exception {
        String customApiUrl = format("http://%s:%d/custom/api/", bitbucket.getHostName(), bitbucket.getPort());
        settings.setProperty(BitbucketSettings.API_URL, customApiUrl);

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john.doe", "John Doe"));
        bitbucket.enqueue(newPrimaryEmailResponse("john.doe@bitbucket.org"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john.doe@bitbucket");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

        RecordedRequest userRequest = bitbucket.takeRequest();
        assertThat(userRequest.getPath()).startsWith("/custom/api/2.0/user");
    }

    private static MockResponse newSuccessfulAccessTokenResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setBody("{\"access_token\":\"e72e16c7e42f292c6912e7710c838347ae178b4a\",\"scope\":\"user\"}");
    }

    private static MockResponse newUserResponse(String login, String displayName) {
        String displayNameJson = displayName != null ? "\"display_name\":\"" + displayName + "\"" : "\"display_name\":null";
        return new MockResponse()
                .setResponseCode(200)
                .setBody("{\"username\":\"" + login + "\", " + displayNameJson + ", \"uuid\":\"{test-uuid}\"}");
    }

    private static MockResponse newPrimaryEmailResponse(String email) {
        return new MockResponse()
                .setResponseCode(200)
                .setBody("{\"values\":[{\"active\": true,\"email\":\"" + email + "\",\"is_primary\": true}]}");
    }

    private static MockResponse newNoPrimaryEmailResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setBody("{\"values\":[{\"active\": true,\"email\":\"secondary@bitbucket.org\",\"is_primary\": false}]}");
    }

    private static MockResponse newTeamsResponse(String... teams) {
        String teamsJson = Arrays.stream(teams)
                .map(team -> "{\"username\":\"" + team + "\"}")
                .collect(Collectors.joining(","));
        String body = teams.length > 0 ? "{\"values\":[" + teamsJson + "]}" : "{\"values\":[]}";
        return new MockResponse()
                .setResponseCode(200)
                .setBody(body);
    }

    private static HttpServletRequest newRequest(String verifierCode) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("code")).thenReturn(verifierCode);
        when(request.getMethod()).thenReturn("GET");
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

        @Override
        public void verifyCsrfState(String parameterName) {
            // Not used in Bitbucket implementation
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