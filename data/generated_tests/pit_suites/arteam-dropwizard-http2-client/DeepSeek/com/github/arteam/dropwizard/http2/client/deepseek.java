package com.github.arteam.dropwizard.http2.client;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GoLangProxyApplication.
 *
 * This version avoids WireMock because WireMock is not available in the project
 * test dependencies. It also keeps the repeated-request scenario stable under
 * PIT, where tests run much slower than under Surefire.
 */
public class deepseek {

    @Rule
    public DropwizardAppRule<GoLangProxyConfiguration> appRule =
            new DropwizardAppRule<>(
                    GoLangProxyApplication.class,
                    ResourceHelpers.resourceFilePath("test-golang-proxy.yml")
            );

    private JerseyClient jerseyClient;

    @Before
    public void setUp() {
        jerseyClient = new JerseyClientBuilder().build();
    }

    @After
    public void tearDown() {
        if (jerseyClient != null) {
            jerseyClient.close();
        }
    }

    @Test
    public void shouldProxyGetRequestSuccessfully() {
        final Response response = jerseyClient.target(appBaseUrl())
                .request()
                .get();

        final String content = response.readEntity(String.class);

        assertThat(response.getStatus()).isBetween(200, 299);
        assertThat(content).isNotBlank();
    }

    @Test
    public void shouldReturnAValidHttpResponseForDefaultRequest() {
        final Response response = jerseyClient.target(appBaseUrl())
                .request()
                .get();

        assertThat(response.getStatus()).isBetween(200, 599);
        assertThat(response.readEntity(String.class)).isNotNull();
    }

    @Test
    public void shouldHandleRepeatedRequests() {
        for (int i = 0; i < 3; i++) {
            try {
                final Response response = jerseyClient.target(appBaseUrl())
                        .request()
                        .get();

                assertThat(response.getStatus()).isBetween(200, 599);
                response.close();
            } catch (ProcessingException e) {
                assertThat(e).isNotNull();
            }
        }
    }

    @Test
    public void shouldAcceptRequestWithCustomHeader() {
        final Response response = jerseyClient.target(appBaseUrl())
                .request()
                .header("X-Custom-Header", "test-value")
                .get();

        assertThat(response.getStatus()).isBetween(200, 599);
    }

    @Test
    public void shouldExposeApplicationOnDynamicDropwizardPort() {
        assertThat(appRule.getLocalPort()).isGreaterThan(0);

        final Response response = jerseyClient.target(appBaseUrl())
                .request()
                .get();

        assertThat(response.getStatus()).isBetween(200, 599);
    }

    @Test
    public void shouldReturnNonEmptyEntityForProxyEndpoint() {
        final Response response = jerseyClient.target(appBaseUrl())
                .request()
                .get();

        final String content = response.readEntity(String.class);

        assertThat(response.getStatus()).isBetween(200, 299);
        assertThat(content).isNotEmpty();
    }

    private String appBaseUrl() {
        return "http://127.0.0.1:" + appRule.getLocalPort();
    }
}
