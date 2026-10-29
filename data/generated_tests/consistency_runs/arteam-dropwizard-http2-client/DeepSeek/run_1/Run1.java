package com.github.arteam.dropwizard.http2.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Application;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.eclipse.jetty.client.HttpClient;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit.WireMockRule;

/**
 * Integration tests for the HTTP/2 proxy that forwards requests to an external service.
 * Uses WireMock to mock the external httpbin.org endpoint and verifies various scenarios
 * including success, error propagation, timeouts, and connection reuse.
 */
public class HttpBinProxyIntegrationTest {

    private static final int WIREMOCK_PORT = 8089;

    @ClassRule
    public static WireMockRule wireMockRule = new WireMockRule(WIREMOCK_PORT);

    private static TestGoLangProxyConfiguration configuration;
    private static DropwizardAppRule<TestGoLangProxyConfiguration> appRule;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(30);

    private JerseyClient jerseyClient;

    @Before
    public void setUp() {
        // Reset WireMock stubs before each test
        wireMockRule.resetAll();

        // Configure the proxy to use the WireMock server as the upstream
        configuration = new TestGoLangProxyConfiguration();
        configuration.setBaseUrl("http://localhost:" + WIREMOCK_PORT);

        // Override client timeouts for specific tests if needed, defaults are fine
        // but we can set them programmatically
        JettyClientConfiguration clientConfig = new JettyClientConfiguration();
        clientConfig.setIdleTimeout(io.dropwizard.util.Duration.seconds(2));
        clientConfig.setConnectionTimeout(io.dropwizard.util.Duration.seconds(1));
        configuration.setH2Client(clientConfig);

        // Start Dropwizard app with custom config
        appRule = new DropwizardAppRule<>(TestGoLangProxyApplication.class, configuration);
        appRule.before(); // manually start before each test

        jerseyClient = new JerseyClientBuilder().build();
    }

    @After
    public void tearDown() {
        if (jerseyClient != null) {
            jerseyClient.close();
        }
        if (appRule != null) {
            appRule.after();
        }
    }

    @Test
    public void shouldProxySuccessfully() {
        // Given: external service returns a successful response with expected Host header
        String expectedBody = "{\"headers\": {\"Host\": \"httpbin.org\"}}";
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(expectedBody)));

        // When: calling the proxy endpoint
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get();

        // Then: response is 200 and body matches the upstream response
        assertThat(response.getStatus()).isEqualTo(200);
        String content = response.readEntity(String.class);
        assertThat(content).isEqualTo(expectedBody);
        assertThat(content).contains("\"Host\": \"httpbin.org\"");
    }

    @Test
    public void shouldHandleUpstreamError() {
        // Given: external service returns a 500 error with a custom body
        String errorBody = "{\"error\": \"Internal Server Error\"}";
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody(errorBody)));

        // When: calling the proxy endpoint
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get();

        // Then: proxy returns 200 (since it returns the raw content) with the error body
        assertThat(response.getStatus()).isEqualTo(200);
        String content = response.readEntity(String.class);
        assertThat(content).isEqualTo(errorBody);
    }

    @Test
    public void shouldTimeoutWhenUpstreamSlow() {
        // Given: upstream takes longer than the configured idle timeout (2 seconds)
        // We set a delay of 3 seconds
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"ok\": true}")
                        .withFixedDelay(3000)));

        // When: calling the proxy, we expect an exception due to read timeout
        assertThatThrownBy(() ->
                jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                        .request()
                        .get()
        ).isInstanceOf(javax.ws.rs.ProcessingException.class)
                .hasMessageContaining("Timeout");
    }

    @Test
    public void tenureTest() throws Exception {
        // Simulate connection reuse and forced reopening as in the original test,
        // but with WireMock to control the responses.
        // Stub the upstream to always return a valid response.
        String expectedBody = "{\"headers\": {\"Host\": \"httpbin.org\"}}";
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(expectedBody)));

        long start = System.currentTimeMillis();
        long now = start;
        int attempt = 1;
        while (now - start < 60000) {
            String content = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                    .request()
                    .get()
                    .readEntity(String.class);
            assertThat(content).contains("\"Host\": \"httpbin.org\"");
            now = System.currentTimeMillis();
            if (attempt++ % 5 == 0) {
                Thread.sleep(4000); // Force connection reopening (idle timeout is 2s)
                attempt = 1;
            }
        }
    }

    // ---------- Custom configuration and application for test ----------

    public static class TestGoLangProxyConfiguration extends GoLangProxyConfiguration {
        private String baseUrl;

        @JsonProperty
        public String getBaseUrl() {
            return baseUrl;
        }

        @JsonProperty
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class TestGoLangProxyApplication extends Application<TestGoLangProxyConfiguration> {
        @Override
        public void run(TestGoLangProxyConfiguration configuration, Environment environment) throws Exception {
            HttpClient httpClient = new JettyClientBuilder(environment)
                    .using(configuration.getH2Client())
                    .build("dropwizard-http2-golang-test");
            environment.jersey().register(new TestHttpBinProxyResource(httpClient, configuration.getBaseUrl()));
        }
    }

    @Path("/")
    @Produces(MediaType.TEXT_PLAIN)
    public static class TestHttpBinProxyResource {
        private final HttpClient httpClient;
        private final String baseUrl;

        public TestHttpBinProxyResource(HttpClient httpClient, String baseUrl) {
            this.httpClient = httpClient;
            this.baseUrl = baseUrl;
        }

        @GET
        public String get() throws Exception {
            return httpClient.GET(baseUrl + "/get").getContentAsString();
        }
    }
}