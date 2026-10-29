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

import javax.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

@Ignore("Takes a lot of time / depends on external httpbin.org availability")
public class HttpBinProxyIntegrationTestGemini {

    private static final long TENURE_DURATION_MILLIS = 60000;
    private static final int REQUESTS_PER_BURST = 5;
    private static final long IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS = 4000;

    @Rule
    public DropwizardAppRule<GoLangProxyConfiguration> appRule = new DropwizardAppRule<>(
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

    private String baseUri() {
        return "http://127.0.0.1:" + appRule.getLocalPort();
    }

    @Test
    public void tenureTest() throws Exception {
        long start = System.currentTimeMillis();
        long now = start;
        int attempt = 1;
        while (now - start < TENURE_DURATION_MILLIS) {
            String content = jerseyClient.target(baseUri())
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

    @Test
    public void shouldReturnOkAndTextPlainForProxiedGet() {
        Response response = jerseyClient.target(baseUri())
                .request()
                .get();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getMediaType().toString()).contains("text/plain");

        String content = response.readEntity(String.class);
        assertThat(content).contains("\"Host\": \"httpbin.org\"");
    }

    @Test
    public void shouldReuseConnectionForConsecutiveRequestsWithoutDelay() {
        for (int i = 0; i < REQUESTS_PER_BURST; i++) {
            String content = jerseyClient.target(baseUri())
                    .request()
                    .get()
                    .readEntity(String.class);
            assertThat(content).contains("\"Host\": \"httpbin.org\"");
        }
    }

    @Test
    public void shouldReopenConnectionAfterIdleTimeoutExpires() throws Exception {
        String firstContent = jerseyClient.target(baseUri())
                .request()
                .get()
                .readEntity(String.class);
        assertThat(firstContent).contains("\"Host\": \"httpbin.org\"");

        Thread.sleep(IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS);

        String secondContent = jerseyClient.target(baseUri())
                .request()
                .get()
                .readEntity(String.class);
        assertThat(secondContent).contains("\"Host\": \"httpbin.org\"");
    }

    @Test
    public void shouldReportHealthyApplication() {
        JerseyClient adminClient = new JerseyClientBuilder().build();
        try {
            Response response = adminClient.target("http://127.0.0.1:" + appRule.getAdminPort())
                    .path("/healthcheck")
                    .request()
                    .get();
            assertThat(response.getStatus()).isEqualTo(200);
            String body = response.readEntity(String.class);
            assertThat(body).contains("\"fake\"");
            assertThat(body).contains("\"healthy\":true");
        } finally {
            adminClient.close();
        }
    }
}