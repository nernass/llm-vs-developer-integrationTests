package com.github.arteam.dropwizard.http2.client;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpBinProxyIntegrationTestGemini {

    private static final long TENURE_DURATION_MILLIS = 60000L;
    private static final int REQUESTS_PER_BURST = 5;
    private static final long IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS = 4000L;

    @Rule
    public DropwizardAppRule<GoLangProxyConfiguration> appRule = new DropwizardAppRule<>(
            GoLangProxyApplication.class,
            ResourceHelpers.resourceFilePath("test-golang-proxy.yml"));

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
    public void shouldStartApplicationOnRandomApplicationAndAdminPorts() {
        assertThat(appRule.getLocalPort()).isGreaterThan(0);
        assertThat(appRule.getAdminPort()).isGreaterThan(0);
    }

    @Test
    public void shouldLoadHttp2ClientConfigurationFromYaml() {
        GoLangProxyConfiguration configuration = appRule.getConfiguration();

        assertThat(configuration).isNotNull();
        assertThat(configuration.getH2Client()).isNotNull();
        assertThat(configuration.getH2Client().getConnectionTimeout().toMilliseconds()).isEqualTo(2000L);
        assertThat(configuration.getH2Client().getIdleTimeout().toMilliseconds()).isEqualTo(2000L);
        assertThat(configuration.getH2Client().isStoreCookies()).isFalse();
        assertThat(configuration.getH2Client().isFollowRedirects()).isTrue();
        assertThat(configuration.getH2Client().getConnectionFactoryBuilder()).isNotNull();
    }

    @Test
    public void shouldExposeHealthyAdminEndpoint() {
        Response response = jerseyClient.target(adminUrl() + "/healthcheck")
                .request()
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(200);
            String body = response.readEntity(String.class);
            assertThat(body).contains("fake");
            assertThat(body).contains("healthy");
        } finally {
            response.close();
        }
    }

    @Test
    public void shouldReturnNotFoundForUnknownApplicationPath() {
        Response response = jerseyClient.target(applicationUrl() + "/missing")
                .request()
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(404);
        } finally {
            response.close();
        }
    }

    @Test
    public void shouldRejectPostRequestsToProxyRoot() {
        Response response = jerseyClient.target(applicationUrl())
                .request()
                .post(Entity.text("payload"));

        try {
            assertThat(response.getStatus()).isEqualTo(405);
        } finally {
            response.close();
        }
    }

    @Test
    @Ignore("Requires outbound access to https://httpbin.org")
    public void shouldProxyHttpBinResponseThroughApplication() {
        Response response = jerseyClient.target(applicationUrl())
                .request()
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(200);
            String content = response.readEntity(String.class);
            assertThat(content).contains("\"Host\": \"httpbin.org\"");
        } finally {
            response.close();
        }
    }

    @Test
    @Ignore("Requires outbound access to https://httpbin.org")
    public void shouldReuseUpstreamConnectionAcrossBurstRequests() {
        for (int i = 0; i < REQUESTS_PER_BURST; i++) {
            String content = jerseyClient.target(applicationUrl())
                    .request()
                    .get()
                    .readEntity(String.class);

            assertThat(content).contains("\"Host\": \"httpbin.org\"");
        }
    }

    @Test
    @Ignore("Requires outbound access to https://httpbin.org")
    public void shouldReopenUpstreamConnectionAfterIdleTimeoutExpires() throws Exception {
        String firstContent = jerseyClient.target(applicationUrl())
                .request()
                .get()
                .readEntity(String.class);

        Thread.sleep(IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS);

        String secondContent = jerseyClient.target(applicationUrl())
                .request()
                .get()
                .readEntity(String.class);

        assertThat(firstContent).contains("\"Host\": \"httpbin.org\"");
        assertThat(secondContent).contains("\"Host\": \"httpbin.org\"");
    }

    @Test
    @Ignore("Long-running endurance test requiring outbound access to https://httpbin.org")
    public void tenureTest() throws Exception {
        long start = System.currentTimeMillis();
        long now = start;
        int attempt = 1;

        while (now - start < TENURE_DURATION_MILLIS) {
            String content = jerseyClient.target(applicationUrl())
                    .request()
                    .get()
                    .readEntity(String.class);

            assertThat(content).contains("\"Host\": \"httpbin.org\"");

            now = System.currentTimeMillis();
            if (attempt++ % REQUESTS_PER_BURST == 0) {
                Thread.sleep(IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS);
                attempt = 1;
            }
        }
    }

    private String applicationUrl() {
        return "http://127.0.0.1:" + appRule.getLocalPort();
    }

    private String adminUrl() {
        return "http://127.0.0.1:" + appRule.getAdminPort();
    }
}