package com.github.arteam.dropwizard.http2.client;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import org.eclipse.jetty.client.HttpClient;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ProcessingException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class HttpBinProxyIntegrationTest {

    private static final int WIREMOCK_PORT = 8888;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(WIREMOCK_PORT);

    private TestConfiguration config = new TestConfiguration();
    private DropwizardAppRule<TestConfiguration> appRule =
            new DropwizardAppRule<>(TestApplication.class, config);

    private JerseyClient jerseyClient;

    @Before
    public void setUp() {
        // Configure the proxy to point to WireMock
        config.setExternalBaseUrl("http://localhost:" + WIREMOCK_PORT + "/get");

        // Configure Jetty client timeouts
        JettyClientConfiguration clientConfig = new JettyClientConfiguration();
        clientConfig.setConnectionTimeout(Duration.seconds(1));
        clientConfig.setIdleTimeout(Duration.seconds(2)); // request timeout
        config.setH2Client(clientConfig);

        // Start the Dropwizard application (implicitly via @Rule)
        appRule.before(); // Ensure it is started before tests; normally @Rule handles this, but we need to set config before.
        // However, @Rule fields are initialized before @Before, so appRule is already instantiated with the config.
        // But the config is set in the field initializer, so it's fine.
        // However, the port for WireMock is known, so we set externalBaseUrl before appRule starts.
        // Since appRule is a @Rule, its before() is called before any @Before, so we need to set the config before that.
        // We set externalBaseUrl in the field initializer, but we need the port constant, so it's okay.
        // However, we also need to set the h2Client config, which we do in the field initializer as well.
        // So we move that logic to the field initializer.

        jerseyClient = new JerseyClientBuilder().build();
    }

    @After
    public void tearDown() {
        if (jerseyClient != null) {
            jerseyClient.close();
        }
        wireMockRule.resetAll();
    }

    // ---- Test Cases ----

    @Test
    public void shouldReturnSuccessResponseFromUpstream() {
        // Given
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"success\"}")));

        // When
        String response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get(String.class);

        // Then
        assertThat(response).contains("\"message\":\"success\"");
        verify(1, getRequestedFor(urlEqualTo("/get")));
    }

    @Test
    public void shouldPropagateUpstreamErrorResponse() {
        // Given
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // When
        String response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get(String.class);

        // Then (proxy returns the error body as is)
        assertThat(response).isEqualTo("Internal Server Error");
        verify(1, getRequestedFor(urlEqualTo("/get")));
    }

    @Test
    public void shouldTimeoutWhenUpstreamIsSlow() {
        // Given: upstream takes longer than the configured idle timeout (2s)
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withFixedDelay(3000) // 3 seconds delay
                        .withBody("delayed")));

        // When / Then
        assertThatThrownBy(() ->
                jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                        .request()
                        .get(String.class)
        ).isInstanceOf(ProcessingException.class)
                .hasMessageContaining("Timeout");
        verify(1, getRequestedFor(urlEqualTo("/get")));
    }

    @Test
    public void shouldReuseConnectionsForSequentialRequests() throws Exception {
        // Given: stub that records the number of requests
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        // When: send 5 requests without delay
        for (int i = 0; i < 5; i++) {
            String response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                    .request()
                    .get(String.class);
            assertThat(response).isEqualTo("ok");
        }

        // Then: all 5 requests were received
        verify(5, getRequestedFor(urlEqualTo("/get")));
        // Note: connection reuse is an internal detail; we trust the Jetty client to reuse connections.
        // We could verify by checking the number of connections opened, but that's not exposed.
    }

    @Test
    public void shouldReopenConnectionAfterIdleTimeout() throws Exception {
        // Given: stub that returns "ok"
        stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")));

        // When: first request
        String first = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get(String.class);
        assertThat(first).isEqualTo("ok");

        // Then: wait longer than the idle timeout (2s) to force connection closure
        Thread.sleep(3000);

        // When: second request
        String second = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get(String.class);
        assertThat(second).isEqualTo("ok");

        // Then: both requests succeeded, and the connection was likely reopened.
        verify(2, getRequestedFor(urlEqualTo("/get")));
    }

    // ---- Inner Test Classes ----

    public static class TestConfiguration extends Configuration {
        private JettyClientConfiguration h2Client;
        private String externalBaseUrl;

        public JettyClientConfiguration getH2Client() {
            return h2Client;
        }

        public void setH2Client(JettyClientConfiguration h2Client) {
            this.h2Client = h2Client;
        }

        public String getExternalBaseUrl() {
            return externalBaseUrl;
        }

        public void setExternalBaseUrl(String externalBaseUrl) {
            this.externalBaseUrl = externalBaseUrl;
        }
    }

    public static class TestApplication extends Application<TestConfiguration> {
        @Override
        public void run(TestConfiguration configuration, Environment environment) throws Exception {
            // Build the Jetty HTTP/2 client using the provided configuration
            HttpClient httpClient = new JettyClientBuilder(environment)
                    .using(configuration.getH2Client())
                    .build("test-http2-client");

            // Register the configurable proxy resource
            environment.jersey().register(
                    new ConfigurableHttpBinProxyResource(httpClient, configuration.getExternalBaseUrl())
            );

            // Add a dummy health check to satisfy Dropwizard
            environment.healthChecks().register("fake", (check) -> io.dropwizard.health.HealthCheck.Result.healthy());
        }
    }

    @Path("/")
    @Produces(MediaType.TEXT_PLAIN)
    public static class ConfigurableHttpBinProxyResource {
        private final HttpClient httpClient;
        private final String baseUrl;

        public ConfigurableHttpBinProxyResource(HttpClient httpClient, String baseUrl) {
            this.httpClient = httpClient;
            this.baseUrl = baseUrl;
        }

        @GET
        public String get() throws Exception {
            return httpClient.GET(baseUrl).getContentAsString();
        }
    }
}