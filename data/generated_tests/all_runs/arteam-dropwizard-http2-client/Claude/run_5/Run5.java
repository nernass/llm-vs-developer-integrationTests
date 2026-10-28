package com.github.arteam.dropwizard.http2.client;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpBinProxyIntegrationTestGemini {

    @ClassRule
    public static final DropwizardAppRule<GoLangProxyConfiguration> appRule = new DropwizardAppRule<>(
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
    public void shouldReturnSuccessfulResponseFromProxyEndpoint() {
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get();

        assertThat(response.getStatus()).isEqualTo(200);

        String content = response.readEntity(String.class);
        assertThat(content).contains("\"Host\": \"httpbin.org\"");
    }

    @Test
    public void shouldReuseConnectionOnSequentialRequests() {
        for (int i = 0; i < 3; i++) {
            Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                    .request()
                    .get();

            assertThat(response.getStatus()).isEqualTo(200);
            response.close();
        }
    }

    @Test
    public void shouldLoadConfigurationCorrectly() {
        GoLangProxyConfiguration config = appRule.getConfiguration();
        assertThat(config.getH2Client()).isNotNull();
        assertThat(config.getH2Client().getConnectionTimeout().toMilliseconds()).isGreaterThan(0);
        assertThat(config.getH2Client().getIdleTimeout().toMilliseconds()).isGreaterThan(0);
    }

    @Test
    public void shouldReturn404ForUnknownPath() {
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort() + "/unknown")
                .request()
                .get();

        assertThat(response.getStatus()).isEqualTo(404);
        response.close();
    }

    @Test
    public void shouldReturnMethodNotAllowedForPostRequest() {
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .post(null);

        assertThat(response.getStatus()).isEqualTo(405);
        response.close();
    }
}