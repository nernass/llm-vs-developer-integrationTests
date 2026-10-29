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

/**
 * Integration tests for the GoLang/httpbin proxy sample application.
 * <p>
 * Follows the pattern of {@link HttpBinProxyIntegrationTest}: boots a real embedded
 * Dropwizard application (via {@link DropwizardAppRule}) backed by a real Jetty HTTP/2
 * client, which proxies requests to the real, external https://httpbin.org/get endpoint.
 * No mocks are used, matching the analyzed mocking strategy (everything in the flow is real).
 *
 * @author Artem Prigoda (a.prigoda)
 */
@Ignore("Takes a lot of time / depends on external httpbin.org availability")
public class HttpBinProxyIntegrationTestGemini {

    private static final long TENURE_DURATION_MILLIS = 60000;
    private static final int REQUESTS_PER_BURST = 5;
    private static final long IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS = 4000; // config idleTimeout is 2s

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

    private String baseUri() {
        return "http://127.0.0.1:" + appRule.getLocalPort();
    }

    /**
     * Endurance test: repeatedly calls the local proxy endpoint for up to 60 seconds,
     * verifying every response contains the expected httpbin.org Host field.
     * Also exercises connection reuse (bursts of 5 requests) and connection
     * re-establishment (sleeping past the configured 2s idle timeout every 5th request).
     */
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
            if (attempt++ % REQUESTS_PER_BURST == 0) { // 5 attempts reuse the same connection
                Thread.sleep(IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS); // Force reopening the connection
                attempt = 1;
            }
        }
    }

    /**
     * Verifies a single proxied call returns HTTP 200 and a text/plain content type,
     * matching the @Produces(MediaType.TEXT_PLAIN) contract on HttpBinProxyResource.
     */
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

    /**
     * Exercises connection reuse in isolation: five consecutive requests without any
     * delay should all succeed on what is expected to be the same underlying connection,
     * since the idle timeout (2s) is never exceeded between calls.
     */
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

    /**
     * Exercises connection re-establishment: sleeping longer than the configured
     * idleTimeout (2s) between two requests forces the Jetty HTTP client to open a
     * new connection to httpbin.org, and the proxy call must still succeed afterward.
     */
    @Test
    public void shouldReopenConnectionAfterIdleTimeoutExpires() throws Exception {
        String firstContent = jerseyClient.target(baseUri())
                .request()
                .get()
                .readEntity(String.class);
        assertThat(firstContent).contains("\"Host\": \"httpbin.org\"");

        Thread.sleep(IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS); // exceeds configured 2s idleTimeout

        String secondContent = jerseyClient.target(baseUri())
                .request()
                .get()
                .readEntity(String.class);
        assertThat(secondContent).contains("\"Host\": \"httpbin.org\"");
    }

    /**
     * Sanity check that the embedded Dropwizard application's admin/health check
     * endpoint reports healthy, confirming the app (and its wired Jetty HttpClient
     * managed lifecycle) started correctly.
     */
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