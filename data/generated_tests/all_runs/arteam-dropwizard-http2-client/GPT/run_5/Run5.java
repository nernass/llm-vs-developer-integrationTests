package com.github.arteam.dropwizard.http2.client;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.arteam.dropwizard.http2.client.transport.Http2ClearClientTransportFactory;
import io.dropwizard.Configuration;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.logging.BootstrapLogging;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.FixtureHelpers;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.component.LifeCycle;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpBinProxyIntegrationTest {

    static {
        BootstrapLogging.bootstrap();
    }

    private static final long TENURE_DURATION_MILLIS = 60000L;
    private static final int REQUESTS_PER_BURST = 5;
    private static final long IDLE_TIMEOUT_EXCEEDING_SLEEP_MILLIS = 4000L;

    @ClassRule
    public static final DropwizardAppRule<TestConfiguration> h2cRule = new DropwizardAppRule<>(
            TestApplication.class,
            ResourceHelpers.resourceFilePath("http2c-server.yml")
    );

    @Rule
    public DropwizardAppRule<GoLangProxyConfiguration> proxyRule = new DropwizardAppRule<>(
            GoLangProxyApplication.class,
            ResourceHelpers.resourceFilePath("test-golang-proxy.yml")
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    public void shouldLoadProxyConfigurationFromYaml() {
        GoLangProxyConfiguration configuration = proxyRule.getConfiguration();

        assertThat(configuration).isNotNull();
        assertThat(configuration.getH2Client()).isNotNull();
        assertThat(configuration.getH2Client().getConnectionTimeout().toMilliseconds()).isEqualTo(2000L);
        assertThat(configuration.getH2Client().getIdleTimeout().toMilliseconds()).isEqualTo(2000L);
        assertThat(configuration.getH2Client().isStoreCookies()).isFalse();
        assertThat(configuration.getH2Client().isFollowRedirects()).isTrue();
        assertThat(configuration.getH2Client().getConnectionFactoryBuilder()).isNotNull();
    }

    @Test
    public void shouldStartApplicationOnRandomPorts() {
        assertThat(proxyRule.getLocalPort()).isGreaterThan(0);
        assertThat(proxyRule.getAdminPort()).isGreaterThan(0);
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
    public void shouldRejectPostOnProxyRoot() {
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
    public void shouldBuildManagedHttp2ClearClientAgainstLocalServer() throws Exception {
        JettyClientConfiguration configuration = new JettyClientConfiguration();
        configuration.setConnectionFactoryBuilder(new Http2ClearClientTransportFactory());

        ManagedClient managedClient = buildManagedClient(configuration, "http-bin-proxy-h2c-client");
        try {
            String payload = managedClient.httpClient
                    .GET(String.format("http://127.0.0.1:%d/application/greet", h2cRule.getLocalPort()))
                    .getContentAsString();

            assertThat(objectMapper.readTree(payload))
                    .isEqualTo(objectMapper.readTree(FixtureHelpers.fixture("server_response.json")));
        } finally {
            managedClient.close();
        }
    }

    @Test
    public void shouldApplyConfiguredTimeoutsAndUserAgentWhenBuildingManagedClient() throws Exception {
        JettyClientConfiguration configuration = new JettyClientConfiguration();
        configuration.setConnectionFactoryBuilder(new Http2ClearClientTransportFactory());
        configuration.setConnectionTimeout(Duration.seconds(3));
        configuration.setIdleTimeout(Duration.seconds(4));

        ManagedClient managedClient = buildManagedClient(configuration, "proxy-gemini-agent");
        try {
            assertThat(managedClient.httpClient.getConnectTimeout()).isEqualTo(3000L);
            assertThat(managedClient.httpClient.getIdleTimeout()).isEqualTo(4000L);
            assertThat(managedClient.httpClient.getUserAgentField().getValue()).isEqualTo("proxy-gemini-agent");
            assertThat(managedClient.httpClient.getCookieStore().getCookies()).isEmpty();
        } finally {
            managedClient.close();
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

    private ManagedClient buildManagedClient(JettyClientConfiguration configuration, String name) throws Exception {
        Environment environment = new Environment(
                "http-bin-proxy-test",
                objectMapper,
                Validators.newValidatorFactory(),
                new MetricRegistry(),
                ClassLoader.getSystemClassLoader(),
                new HealthCheckRegistry(),
                new Configuration()
        );

        HttpClient httpClient = new JettyClientBuilder(environment)
                .using(configuration)
                .build(name);

        for (LifeCycle managedObject : environment.lifecycle().getManagedObjects()) {
            managedObject.start();
        }

        return new ManagedClient(environment, httpClient);
    }

    private String applicationUrl() {
        return "http://127.0.0.1:" + proxyRule.getLocalPort();
    }

    private String adminUrl() {
        return "http://127.0.0.1:" + proxyRule.getAdminPort();
    }

    private static class ManagedClient {
        private final Environment environment;
        private final HttpClient httpClient;

        private ManagedClient(Environment environment, HttpClient httpClient) {
            this.environment = environment;
            this.httpClient = httpClient;
        }

        private void close() throws Exception {
            for (LifeCycle managedObject : environment.lifecycle().getManagedObjects()) {
                if (managedObject.isRunning()) {
                    managedObject.stop();
                }
            }
        }
    }
}