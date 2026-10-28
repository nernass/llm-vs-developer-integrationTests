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

import com.github.scribejava.core.model.Verb;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.net.URLEncoder.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BitbucketAuthenticationWireMockIT {

  private static final String CALLBACK_URL = "http://localhost/oauth/callback/bitbucket";

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private final MapSettings settings = new MapSettings(new PropertyDefinitions(BitbucketSettings.definitions()));
  private final BitbucketSettings bitbucketSettings = new BitbucketSettings(settings.asConfig());
  private final UserIdentityFactory userIdentityFactory = new UserIdentityFactory(bitbucketSettings);
  private final BitbucketScribeApi scribeApi = new BitbucketScribeApi(bitbucketSettings);
  private final BitbucketIdentityProvider underTest = new BitbucketIdentityProvider(bitbucketSettings, userIdentityFactory, scribeApi);

  private WireMockServer wireMock;

  @Before
  public void setUp() {
    wireMock = new WireMockServer(options().dynamicPort());
    wireMock.start();

    settings.setProperty(BitbucketSettings.CONSUMER_KEY, "the_id");
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, "the_secret");
    settings.setProperty(BitbucketSettings.ENABLED, true);
    settings.setProperty(BitbucketSettings.LOGIN_STRATEGY, BitbucketSettings.LOGIN_STRATEGY_UNIQUE);
    settings.setProperty(BitbucketSettings.API_URL, wireMock.baseUrl());
    settings.setProperty(BitbucketSettings.WEB_URL, wireMock.baseUrl());
  }

  @After
  public void tearDown() {
    wireMock.resetAll();
    wireMock.stop();
  }

  @Test
  public void shouldExposeProviderMetadataAndDefaultSettings() {
    assertThat(underTest.getKey()).isEqualTo(BitbucketIdentityProvider.KEY);
    assertThat(underTest.getName()).isEqualTo("Bitbucket");
    assertThat(underTest.getDisplay().getIconPath()).isEqualTo("/static/authbitbucket/bitbucket.svg");
    assertThat(underTest.getDisplay().getBackgroundColor()).isEqualTo("#0052cc");
    assertThat(underTest.isEnabled()).isTrue();
    assertThat(underTest.allowsUsersToSignUp()).isTrue();
  }

  @Test
  public void shouldRespectAllowUsersToSignUpSetting() {
    settings.setProperty(BitbucketSettings.ALLOW_USERS_TO_SIGN_UP, false);

    assertThat(underTest.allowsUsersToSignUp()).isFalse();
  }

  @Test
  public void shouldBeDisabledWhenClientIdIsMissing() {
    settings.setProperty(BitbucketSettings.CONSUMER_KEY, (String) null);

    assertThat(underTest.isEnabled()).isFalse();
  }

  @Test
  public void shouldBeDisabledWhenClientSecretIsMissing() {
    settings.setProperty(BitbucketSettings.CONSUMER_SECRET, (String) null);

    assertThat(underTest.isEnabled()).isFalse();
  }

  @Test
  public void shouldExposeConfiguredAccessTokenEndpointAndVerb() {
    assertThat(scribeApi.getAccessTokenEndpoint()).isEqualTo(wireMock.baseUrl() + "/site/oauth2/access_token");
    assertThat(scribeApi.getAccessTokenVerb()).isEqualTo(Verb.POST);
  }

  @Test
  public void shouldRedirectBrowserToBitbucketAuthenticationForm() throws Exception {
    DumbInitContext context = new DumbInitContext("the-csrf-state");

    underTest.init(context);

    assertThat(context.redirectedTo)
      .startsWith(wireMock.baseUrl() + "/site/oauth2/authorize")
      .contains("response_type=code")
      .contains("client_id=the_id")
      .contains("redirect_uri=" + encode(CALLBACK_URL, StandardCharsets.UTF_8.name()))
      .contains("scope=" + encode(BitbucketIdentityProvider.REQUIRED_SCOPE, StandardCharsets.UTF_8.name()));
  }

  @Test
  public void shouldFailToInitWhenProviderIsDisabled() {
    settings.setProperty(BitbucketSettings.ENABLED, false);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Bitbucket authentication is disabled");

    underTest.init(new DumbInitContext("the-csrf-state"));
  }

  @Test
  public void shouldAuthenticateSuccessfullyAndCallBitbucketEndpointsInOrder() {
    stubSuccessfulAccessTokenResponse();
    stubUserResponse("john", "John", "john-uuid");
    stubPrimaryEmailResponse("john@bitbucket.org");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(context);

    assertThat(context.csrfStateVerified.get()).isFalse();
    assertThat(context.redirectedToRequestedPage.get()).isTrue();
    assertThat(context.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(context.userIdentity.getName()).isEqualTo("John");
    assertThat(context.userIdentity.getEmail()).isEqualTo("john@bitbucket.org");
    assertThat(context.userIdentity.getProviderId()).isEqualTo("john-uuid");

    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/site/oauth2/access_token")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user")));
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user/emails")));
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/2.0/teams")));

    assertThat(wireMock.getAllServeEvents()).hasSize(3);
    assertThat(wireMock.getAllServeEvents().get(0).getRequest().getUrl()).startsWith("/site/oauth2/access_token");
    assertThat(wireMock.getAllServeEvents().get(1).getRequest().getUrl()).isEqualTo("/2.0/user");
    assertThat(wireMock.getAllServeEvents().get(2).getRequest().getUrl()).isEqualTo("/2.0/user/emails");
  }

  @Test
  public void shouldAuthenticateSuccessfullyWithProviderLoginStrategy() {
    settings.setProperty(BitbucketSettings.LOGIN_STRATEGY, BitbucketSettings.LOGIN_STRATEGY_PROVIDER_LOGIN);

    stubSuccessfulAccessTokenResponse();
    stubUserResponse("john", "John", "john-uuid");
    stubPrimaryEmailResponse("john@bitbucket.org");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(context);

    assertThat(context.userIdentity.getLogin()).isEqualTo("john");
    assertThat(context.userIdentity.getName()).isEqualTo("John");
    assertThat(context.userIdentity.getProviderId()).isEqualTo("john-uuid");
  }

  @Test
  public void shouldUseUsernameWhenDisplayNameIsEmpty() {
    stubSuccessfulAccessTokenResponse();
    stubUserResponse("john", "", "john-uuid");
    stubPrimaryEmailResponse("john@bitbucket.org");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(context);

    assertThat(context.userIdentity.getName()).isEqualTo("john");
    assertThat(context.userIdentity.getLogin()).isEqualTo("john@bitbucket");
  }

  @Test
  public void shouldAuthenticateWithoutEmailWhenEmailEndpointFails() {
    stubSuccessfulAccessTokenResponse();
    stubUserResponse("john", "John", "john-uuid");
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user/emails"))
      .willReturn(aResponse().withStatus(500).withBody("{error}")));

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(context);

    assertThat(context.redirectedToRequestedPage.get()).isTrue();
    assertThat(context.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    assertThat(context.userIdentity.getEmail()).isNull();
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/user/emails")));
  }

  @Test
  public void shouldThrowIllegalStateExceptionWhenUserProfileRequestFails() {
    stubSuccessfulAccessTokenResponse();
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(aResponse().withStatus(500).withBody("{error}")));

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));

    try {
      underTest.callback(context);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Can not get Bitbucket user profile. HTTP code: 500, response: {error}");
      assertThat(context.csrfStateVerified.get()).isFalse();
      assertThat(context.userIdentity).isNull();
      assertThat(context.redirectedToRequestedPage.get()).isFalse();
    }
  }

  @Test
  public void shouldAllowAuthenticationWhenUserBelongsToAtLeastOneRestrictedTeam() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    stubSuccessfulAccessTokenResponse();
    stubUserResponse("john", "John", "john-uuid");
    stubPrimaryEmailResponse("john@bitbucket.org");
    stubTeamsResponse("team3", "team2");

    DumbCallbackContext context = new DumbCallbackContext(newRequest("the-verifier-code"));

    underTest.callback(context);

    assertThat(context.redirectedToRequestedPage.get()).isTrue();
    assertThat(context.userIdentity.getLogin()).isEqualTo("john@bitbucket");
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/2.0/teams")).withQueryParam("role", equalTo("member")));
  }

  @Test
  public void shouldRejectAuthenticationWhenUserDoesNotBelongToRestrictedTeams() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    stubSuccessfulAccessTokenResponse();
    stubUserResponse("john", "John", "john-uuid");
    stubPrimaryEmailResponse("john@bitbucket.org");
    stubTeamsResponse("team3");

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  @Test
  public void shouldRejectAuthenticationWhenUserHasNoTeams() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    stubSuccessfulAccessTokenResponse();
    stubUserResponse("john", "John", "john-uuid");
    stubPrimaryEmailResponse("john@bitbucket.org");
    stubTeamsResponse();

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  @Test
  public void shouldRejectAuthenticationWhenTeamsEndpointFailsUnderRestriction() {
    settings.setProperty(BitbucketSettings.TEAM_RESTRICTION, new String[] {"team1", "team2"});

    stubSuccessfulAccessTokenResponse();
    stubUserResponse("john", "John", "john-uuid");
    stubPrimaryEmailResponse("john@bitbucket.org");
    wireMock.stubFor(get(urlPathEqualTo("/2.0/teams"))
      .withQueryParam("role", equalTo("member"))
      .willReturn(aResponse().withStatus(500).withBody("{error}")));

    expectedException.expect(UnauthorizedException.class);
    expectedException.expectMessage("User john is not part of restricted teams");

    underTest.callback(new DumbCallbackContext(newRequest("the-verifier-code")));
  }

  private void stubSuccessfulAccessTokenResponse() {
    wireMock.stubFor(post(urlPathEqualTo("/site/oauth2/access_token"))
      .willReturn(okJson("{\"access_token\":\"e72e16c7e42f292c6912e7710c838347ae178b4a\",\"scope\":\"user\"}")));
  }

  private void stubUserResponse(String login, String displayName, String uuid) {
    StringBuilder body = new StringBuilder()
      .append("{\"username\":\"").append(login).append("\",")
      .append("\"uuid\":\"").append(uuid).append("\"");
    if (displayName != null) {
      body.append(",\"display_name\":\"").append(displayName).append("\"");
    }
    body.append("}");

    wireMock.stubFor(get(urlPathEqualTo("/2.0/user"))
      .willReturn(okJson(body.toString())));
  }

  private void stubTeamsResponse(String... teams) {
    String values = Arrays.stream(teams)
      .map(team -> "{\"username\":\"" + team + "\"}")
      .collect(Collectors.joining(","));

    wireMock.stubFor(get(urlPathEqualTo("/2.0/teams"))
      .withQueryParam("role", equalTo("member"))
      .willReturn(okJson("{\"values\":[" + values + "]}")));
  }

  private void stubPrimaryEmailResponse(String email) {
    wireMock.stubFor(get(urlPathEqualTo("/2.0/user/emails"))
      .willReturn(okJson("{\"values\":[{\"active\":true,\"email\":\"" + email + "\",\"is_primary\":true}]}")));
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
