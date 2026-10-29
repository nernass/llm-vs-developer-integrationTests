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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class geminiIT {

  private static final String CALLBACK_URL = "http://localhost/oauth/callback/bitbucket";

  private final MapSettings settings = new MapSettings(new PropertyDefinitions(BitbucketSettings.definitions()));
  private final BitbucketSettings bitbucketSettings = new BitbucketSettings(settings.asConfig());
  private final UserIdentityFactory userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
  private final BitbucketScribeApi scribeApi = new BitbucketScribeApi(bitbucketSettings);
  private final BitbucketIdentityProvider underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

  private WireMockServer wireMockServer;

  @Before
  public void setUp() {
    wireMockServer = new WireMockServer(options().dynamicPort());
    wireMockServer.start();

    settings.setProperty(BitbucketSettings.CONSUMER_KEY, "client_id");
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, "client_secret");
    settings.setProperty(BitbucketSettings.ENABLED, true);
    settings.setProperty(BitbucketSettings.API_URL, wireMockServer.baseUrl());
    settings.setProperty(BitbucketSettings.WEB_URL, wireMockServer.baseUrl());
  }

  @After
  public void tearDown() {
    if (wireMockServer != null) {
      wireMockServer.resetAll();
      wireMockServer.stop();
    }
  }

  @Test
  public void shouldRedirectBrowserToBitbucketAuthenticationForm() throws Exception {
    DumbInitContext context = new DumbInitContext("the-csrf-state");
    
    underTest.init(context);
    
    assertThat(context.redirectedTo)
      .startsWith(wireMockServer.baseUrl() + "/site/oauth2/authorize")
      .contains("client_id=client_id")
      .contains("scope=" + encode("account", StandardCharsets.UTF_8.name()))
      .contains("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()));
  }

  @Test
  public void shouldAuthenticateSuccessfully() {
    stubAccessToken();
    stubUserProfile("john", "John Doe");
    stubUserEmails("john@bitbucket.org");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
    underTest.callback(context);

    assertThat(context.csrfStateVerified.get()).isFalse();
    assertThat(context.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(context.userIdentity.getName()).isEqualTo("John Doe");
    assertThat(context.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
    assertThat(context.redirectedToRequestedPage.get()).isTrue();

    wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/site/oauth2/access_token")));
    wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user")));
    wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user/emails")));
  }

  @Test
  public void shouldThrowExceptionWhenUserProfileRequestFails() {
    stubAccessToken();
    wireMockServer.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(aResponse().withStatus(500).withBody("{error}")));

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
    
    try {
      underTest.callback(context);
      fail("Expected IllegalStateException to be thrown");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Can not get Bitbucket user profile. HTTP code: 500, response: {error}");
    }

    assertThat(context.userIdentity).isNull();
    assertThat(context.redirectedToRequestedPage.get()).isFalse();
  }

  @Test
  public void shouldAllowAuthenticationWhenUserIsMemberOfAllowedTeam() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});
    
    stubAccessToken();
    stubUserProfile("john", "John Doe");
    stubUserEmails("john@bitbucket.org");
    stubUserTeams("team3", "team2");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
    underTest.callback(context);

    assertThat(context.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(context.redirectedToRequestedPage.get()).isTrue();
    wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/2.0/teams")).withQueryParam("role", equalTo("member")));
  }

  @Test
  public void shouldForbidAuthenticationWhenUserIsNotMemberOfAllowedTeam() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});
    
    stubAccessToken();
    stubUserProfile("john", "John Doe");
    stubUserEmails("john@bitbucket.org");
    stubUserTeams("team3");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
    
    try {
      underTest.callback(context);
      fail("Expected UnauthorizedException to be thrown");
    } catch (UnauthorizedException e) {
      assertThat(e.getMessage()).isEqualTo("User john is not part of restricted teams");
    }

    assertThat(context.userIdentity).isNull();
  }

  @Test
  public void shouldForbidAuthenticationWhenUserIsNotMemberOfAnyTeam() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});
    
    stubAccessToken();
    stubUserProfile("john", "John Doe");
    stubUserEmails("john@bitbucket.org");
    stubUserTeams(); // no teams

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));
    
    try {
      underTest.callback(context);
      fail("Expected UnauthorizedException to be thrown");
    } catch (UnauthorizedException e) {
      assertThat(e.getMessage()).isEqualTo("User john is not part of restricted teams");
    }

    assertThat(context.userIdentity).isNull();
  }

  private void stubAccessToken() {
    wireMockServer.stubFor(post(urlPathEqualTo("/site/oauth2/access_token"))
      .willReturn(okJson("{\"access_token\":\"dummy-token\",\"scope\":\"user\"}")));
  }

  private void stubUserProfile(String username, String displayName) {
    wireMockServer.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(okJson("{\"username\":\"" + username + "\", \"display_name\":\"" + displayName + "\", \"uuid\":\"12345\"}")));
  }

  private void stubUserEmails(String email) {
    wireMockServer.stubFor(get(urlPathEqualTo("/2.0/user/emails"))
      .willReturn(okJson("{\"values\":[{\"active\": true,\"email\":\"" + email + "\",\"is_primary\": true}]}")));
  }

  private void stubUserTeams(String... teams) {
    String teamsJson = Arrays.stream(teams)
      .map(t -> "{\"username\":\"" + t + "\"}")
      .collect(Collectors.joining(","));
    wireMockServer.stubFor(get(urlPathEqualTo("/2.0/teams"))
      .withQueryParam("role", equalTo("member"))
      .willReturn(okJson("{\"values\":[" + teamsJson + "]}")));
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