package com.github.arteam.dropwizard.http2.client;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.arteam.dropwizard.http2.client.transport.Http2ClearClientTransportFactory;
import io.dropwizard.Configuration;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.logging.BootstrapLogging;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.component.LifeCycle;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpBinProxyIntegrationTestGpt {

    static {
        BootstrapLogging.bootstrap();
    }

    @ClassRule
    public static final DropwizardAppRule<GoLangProxyConfiguration> proxyRule = new DropwizardAppRule<>(
            GoLangProxyApplication.class,
            ResourceHelpers.resourceFilePath("test-golang-proxy.yml")
    );

    @ClassRule
    public static final DropwizardAppRule<TestConfiguration> h2cRule = new DropwizardAppRule<>(
            TestApplication.class,
            ResourceHelpers.resourceFilePath("http2c-server.yml")
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
        assertThat(configuration.getH2Client().getConnectionTimeout().toMilliseconds()).isEqualTo(2000);
        assertThat(configuration.getH2Client().getIdleTimeout().toMilliseconds()).isEqualTo(2000);
    }

    @Test
    public void shouldExposeHealthyAdminEndpoint() {
        Response response = jerseyClient.target("http://127.0.0.1:" + proxyRule.getAdminPort() + "/healthcheck")
                .request()
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(String.class)).contains("fake");
        } finally {
            response.close();
        }
    }

    @Test
    public void shouldReturnNotFoundForUnknownApplicationPath() {
        Response response = jerseyClient.target("http://127.0.0.1:" + proxyRule.getLocalPort() + "/missing")
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
        Response response = jerseyClient.target("http://127.0.0.1:" + proxyRule.getLocalPort())
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

        ManagedClient managedClient = buildManagedClient(configuration, "gpt-h2c-client");
        try {
            String payload = managedClient.client
                    .GET(String.format("http://127.0.0.1:%d/application/greet", h2cRule.getLocalPort()))
                    .getContentAsString();

            JsonNode responseJson = objectMapper.readTree(payload);
            assertThat(responseJson.get("author").asText()).isEqualTo("HTTP/2 server");
            assertThat(responseJson.get("message").asText()).isEqualTo("Hello, World!");
        } finally {
            managedClient.close();
        }
    }

    @Test
    public void shouldApplyConfiguredTimeoutsAndUserAgentToManagedClient() throws Exception {
        JettyClientConfiguration configuration = new JettyClientConfiguration();
        configuration.setConnectionFactoryBuilder(new Http2ClearClientTransportFactory());
        configuration.setConnectionTimeout(io.dropwizard.util.Duration.seconds(3));
        configuration.setIdleTimeout(io.dropwizard.util.Duration.seconds(4));

        ManagedClient managedClient = buildManagedClient(configuration, "gpt-user-agent");
        try {
            assertThat(managedClient.client.getConnectTimeout()).isEqualTo(3000);
            assertThat(managedClient.client.getIdleTimeout()).isEqualTo(4000);
            assertThat(managedClient.client.getUserAgentField().getValue()).isEqualTo("gpt-user-agent");
        } finally {
            managedClient.close();
        }
    }

    @Test
    @Ignore("Requires outbound access to https://httpbin.org")
    public void shouldProxyHttpBinResponseThroughApplication() {
        Response response = jerseyClient.target("http://127.0.0.1:" + proxyRule.getLocalPort())
                .request()
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(String.class)).contains("\"Host\": \"httpbin.org\"");
        } finally {
            response.close();
        }
    }

    @Test
    @Ignore("Requires outbound access to https://httpbin.org")
    public void shouldReopenUpstreamConnectionAfterIdleTimeout() throws Exception {
        String firstResponse = jerseyClient.target("http://127.0.0.1:" + proxyRule.getLocalPort())
                .request()
                .get()
                .readEntity(String.class);

        Thread.sleep(2500L);

        String secondResponse = jerseyClient.target("http://127.0.0.1:" + proxyRule.getLocalPort())
                .request()
                .get()
                .readEntity(String.class);

        assertThat(firstResponse).contains("\"Host\": \"httpbin.org\"");
        assertThat(secondResponse).contains("\"Host\": \"httpbin.org\"");
    }

    private ManagedClient buildManagedClient(JettyClientConfiguration configuration, String name) throws Exception {
        Environment environment = new Environment(
                "gpt-test",
                objectMapper,
                Validators.newValidatorFactory(),
                new MetricRegistry(),
                ClassLoader.getSystemClassLoader(),
                new HealthCheckRegistry(),
                new Configuration()
        );
        HttpClient client = new JettyClientBuilder(environment)
                .using(configuration)
                .build(name);
        for (LifeCycle managedObject : environment.lifecycle().getManagedObjects()) {
            managedObject.start();
        }
        return new ManagedClient(environment, client);
    }

    private static class ManagedClient {

        private final Environment environment;
        private final HttpClient client;

        private ManagedClient(Environment environment, HttpClient client) {
            this.environment = environment;
            this.client = client;
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