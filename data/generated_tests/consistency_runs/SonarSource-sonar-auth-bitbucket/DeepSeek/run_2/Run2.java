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
        settings.setProperty("sonar.auth.bitbucket.clientId.secured", "the_id");
        settings.setProperty("sonar.auth.bitbucket.clientSecret.secured", "the_secret");
        settings.setProperty("sonar.auth.bitbucket.enabled", true);
        settings.setProperty("sonar.auth.bitbucket.apiUrl", format("http://%s:%d", bitbucket.getHostName(), bitbucket.getPort()));
        settings.setProperty("sonar.auth.bitbucket.webUrl", format("http://%s:%d", bitbucket.getHostName(), bitbucket.getPort()));

        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        scribeApi = new BitbucketScribeApi(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);
    }

    @Test
    public void should_redirect_browser_to_bitbucket_authentication_form() throws Exception {
        DumbInitContext context = new DumbInitContext("the-csrf-state");
        underTest.init(context);

        assertThat(context.redirectedTo)
                .startsWith(bitbucket.url("site/oauth2/authorize").toString())
                .contains("scope=" + encode("account", StandardCharsets.UTF_8.name()));
    }

    @Test
    public void should_authenticate_successfully() throws Exception {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", "John"));
        bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.csrfStateVerified.get()).isFalse();
        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
        assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

        RecordedRequest accessTokenRequest = bitbucket.takeRequest();
        assertThat(accessTokenRequest.getPath()).startsWith("/site/oauth2/access_token");

        RecordedRequest userRequest = bitbucket.takeRequest();
        assertThat(userRequest.getPath()).startsWith("/2.0/user");

        RecordedRequest emailRequest = bitbucket.takeRequest();
        assertThat(emailRequest.getPath()).startsWith("/2.0/user/emails");

        assertThat(bitbucket.getRequestCount()).isEqualTo(3);
    }

    @Test
    public void should_authenticate_successfully_with_provider_login_strategy() throws Exception {
        settings.setProperty("sonar.auth.bitbucket.loginStrategy", "Same as Bitbucket login");
        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", "John"));
        bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
        assertThat(callbackContext.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    }

    @Test
    public void should_authenticate_without_email_when_email_endpoint_fails() throws Exception {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", "John"));
        bitbucket.enqueue(new MockResponse().setResponseCode(500));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(callbackContext.userIdentity.getName()).isEqualTo("John");
        assertThat(callbackContext.userIdentity.getEmail()).isNull();
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    }

    @Test
    public void should_authenticate_when_display_name_is_empty() throws Exception {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", ""));
        bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getName()).isEqualTo("john");
        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    }

    @Test
    public void should_throw_ISE_when_error_requesting_user_profile() {
        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(new MockResponse().setResponseCode(500).setBody("{error}"));

        DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("the-verifier-code"));

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("Can not get Bitbucket user profile. HTTP code: 500, response: {error}");

        underTest.callback(callbackContext);

        assertThat(callbackContext.csrfStateVerified.get()).isTrue();
        assertThat(callbackContext.userIdentity).isNull();
        assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();
    }

    @Test
    public void should_throw_ISE_when_error_requesting_access_token() {
        bitbucket.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}"));

        DumbCallbackContext callbackContext = new DumbCallbackContext(newRequest("invalid-code"));

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("com.github.scribejava.core.exceptions.OAuthException: " +
                "Response body is incorrect. Can't extract a token from this: {\"error\":\"invalid_grant\"}");

        underTest.callback(callbackContext);

        assertThat(callbackContext.csrfStateVerified.get()).isTrue();
        assertThat(callbackContext.userIdentity).isNull();
        assertThat(callbackContext.redirectedToRequestedPage.get()).isFalse();
    }

    @Test
    public void should_allow_authentication_if_user_is_member_of_one_restricted_team() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[]{"team1", "team2"});
        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", "John"));
        bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
        bitbucket.enqueue(newTeamsResponse("team3", "team2"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();
    }

    @Test
    public void should_forbid_authentication_if_user_is_not_member_of_one_restricted_team() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[]{"team1", "team2"});
        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", "John"));
        bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
        bitbucket.enqueue(newTeamsResponse("team3"));

        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage("User john is not part of restricted teams");

        DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
        underTest.callback(context);
    }

    @Test
    public void should_forbid_authentication_if_user_is_not_member_of_any_team() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[]{"team1", "team2"});
        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", "John"));
        bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
        bitbucket.enqueue(newTeamsResponse(/* no teams */));

        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage("User john is not part of restricted teams");

        DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
        underTest.callback(context);
    }

    @Test
    public void should_allow_authentication_when_team_restriction_is_empty() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[0]);
        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", "John"));
        bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));

        HttpServletRequest request = newRequest("the-verifier-code");
        DumbCallbackContext callbackContext = new DumbCallbackContext(request);
        underTest.callback(callbackContext);

        assertThat(callbackContext.userIdentity.getLogin()).isEqualTo("john@bitbucket");
        assertThat(callbackContext.redirectedToRequestedPage.get()).isTrue();

        // Team endpoint should not be called when restriction is empty
        assertThat(bitbucket.getRequestCount()).isEqualTo(3);
    }

    @Test
    public void should_use_proper_team_restriction_case_sensitivity() {
        settings.setProperty("sonar.auth.bitbucket.teams", new String[]{"Team1", "Team2"});
        bitbucketSettings = new BitbucketSettings(settings.asConfig());
        userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
        underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

        bitbucket.enqueue(newSuccessfulAccessTokenResponse());
        bitbucket.enqueue(newUserResponse("john", "John"));
        bitbucket.enqueue(newPrimaryEmailResponse("john@bitbucket.org"));
        bitbucket.enqueue(newTeamsResponse("team1", "team2"));

        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage("User john is not part of restricted teams");

        DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
        underTest.callback(context);
    }

    private static MockResponse newSuccessfulAccessTokenResponse() {
        return new MockResponse().setBody("{\"access_token\":\"e72e16c7e42f292c6912e7710c838347ae178b4a\",\"scope\":\"user\"}");
    }

    private static MockResponse newUserResponse(String login, String name) {
        return new MockResponse().setBody("{\"username\":\"" + login + "\", \"display_name\":\"" + name + "\"}");
    }

    private static MockResponse newTeamsResponse(String... teams) {
        String s = Arrays.stream(teams)
                .map(team -> "{\"username\":\"" + team + "\"}")
                .collect(Collectors.joining(","));
        return new MockResponse().setBody("{\"values\":[" + s + "]}");
    }

    private static MockResponse newPrimaryEmailResponse(String email) {
        return new MockResponse().setBody("{\"values\":[{\"active\": true,\"email\":\"" + email + "\",\"is_primary\": true}]}");
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