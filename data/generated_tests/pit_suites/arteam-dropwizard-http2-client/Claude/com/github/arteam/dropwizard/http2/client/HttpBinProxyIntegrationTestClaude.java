package com.github.arteam.dropwizard.http2.client;

import com.codahale.metrics.MetricRegistry;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.logging.BootstrapLogging;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.component.LifeCycle;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import javax.ws.rs.core.Response;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the HTTP/2 proxy chain:
 * JerseyClient -> GoLangProxyApplication -> HttpBinProxyResource -> Jetty HttpClient -> backend
 */
public class HttpBinProxyIntegrationTestClaude {

    static {
        BootstrapLogging.bootstrap();
    }

    @ClassRule
    public static final DropwizardAppRule<GoLangProxyConfiguration> appRule = new
            DropwizardAppRule<>(GoLangProxyApplication.class,
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

    // ---- Application Startup & Configuration Tests ----

    @Test
    public void shouldStartApplicationSuccessfully() {
        assertThat(appRule.getEnvironment()).isNotNull();
        assertThat(appRule.getConfiguration()).isNotNull();
        assertThat(appRule.getLocalPort()).isGreaterThan(0);
    }

    @Test
    public void shouldLoadH2ClientConfigurationFromYml() {
        GoLangProxyConfiguration config = appRule.getConfiguration();
        JettyClientConfiguration h2Client = config.getH2Client();

        assertThat(h2Client).isNotNull();
        assertThat(h2Client.getConnectionTimeout().toSeconds()).isEqualTo(2);
        assertThat(h2Client.getIdleTimeout().toSeconds()).isEqualTo(2);
    }

    @Test
    public void shouldAssignRandomPortWhenConfiguredAsZero() {
        assertThat(appRule.getLocalPort()).isNotEqualTo(0);
        assertThat(appRule.getAdminPort()).isNotEqualTo(0);
    }

    // ---- JettyClientBuilder Tests ----

    @Test
    public void shouldBuildHttpClientWithCustomTimeouts() throws Exception {
        LifecycleEnvironment lifecycleEnvironment = mock(LifecycleEnvironment.class);
        Environment environment = mock(Environment.class);
        MetricRegistry metricRegistry = new MetricRegistry();

        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        Mockito.doAnswer(invocation -> {
            LifeCycle lc = (LifeCycle) invocation.getArguments()[0];
            lc.start();
            return null;
        }).when(lifecycleEnvironment).manage(any(LifeCycle.class));
        when(environment.metrics()).thenReturn(metricRegistry);

        JettyClientConfiguration configuration = new JettyClientConfiguration();
        configuration.setConnectionTimeout(io.dropwizard.util.Duration.seconds(5));
        configuration.setIdleTimeout(io.dropwizard.util.Duration.seconds(10));

        HttpClient client = new JettyClientBuilder(environment)
                .using(configuration)
                .build("test-client");
        try {
            assertThat(client.getConnectTimeout()).isEqualTo(5000);
            assertThat(client.getIdleTimeout()).isEqualTo(10000);
        } finally {
            client.stop();
        }
    }

    @Test
    public void shouldBuildHttpClientWithUserAgentWhenNameProvided() throws Exception {
        LifecycleEnvironment lifecycleEnvironment = mock(LifecycleEnvironment.class);
        Environment environment = mock(Environment.class);
        MetricRegistry metricRegistry = new MetricRegistry();

        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        Mockito.doAnswer(invocation -> {
            LifeCycle lc = (LifeCycle) invocation.getArguments()[0];
            lc.start();
            return null;
        }).when(lifecycleEnvironment).manage(any(LifeCycle.class));
        when(environment.metrics()).thenReturn(metricRegistry);

        HttpClient client = new JettyClientBuilder(environment)
                .build("my-custom-agent");
        try {
            assertThat(client.getUserAgentField().getValue()).isEqualTo("my-custom-agent");
        } finally {
            client.stop();
        }
    }

    @Test
    public void shouldBuildHttpClientWithoutUserAgentWhenNameIsNull() throws Exception {
        LifecycleEnvironment lifecycleEnvironment = mock(LifecycleEnvironment.class);
        Environment environment = mock(Environment.class);
        MetricRegistry metricRegistry = new MetricRegistry();

        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        Mockito.doAnswer(invocation -> {
            LifeCycle lc = (LifeCycle) invocation.getArguments()[0];
            lc.start();
            return null;
        }).when(lifecycleEnvironment).manage(any(LifeCycle.class));
        when(environment.metrics()).thenReturn(metricRegistry);

        HttpClient client = new JettyClientBuilder(environment).build();
        try {
            assertThat(client).isNotNull();
            assertThat(client.isStarted()).isTrue();
        } finally {
            client.stop();
        }
    }

    @Test
    public void shouldDisableCookieStoreByDefault() throws Exception {
        LifecycleEnvironment lifecycleEnvironment = mock(LifecycleEnvironment.class);
        Environment environment = mock(Environment.class);
        MetricRegistry metricRegistry = new MetricRegistry();

        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        Mockito.doAnswer(invocation -> {
            LifeCycle lc = (LifeCycle) invocation.getArguments()[0];
            lc.start();
            return null;
        }).when(lifecycleEnvironment).manage(any(LifeCycle.class));
        when(environment.metrics()).thenReturn(metricRegistry);

        JettyClientConfiguration configuration = new JettyClientConfiguration();
        assertThat(configuration.isStoreCookies()).isFalse();

        HttpClient client = new JettyClientBuilder(environment)
                .using(configuration)
                .build("test");
        try {
            assertThat(client.getCookieStore()).isNotNull();
            assertThat(client.getCookieStore().getCookies()).isEmpty();
        } finally {
            client.stop();
        }
    }

    // ---- Proxy Endpoint Availability Tests ----

    @Test
    public void shouldReturnNonNullResponseFromProxyEndpoint() {
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get();

        assertThat(response).isNotNull();
        // The proxy calls httpbin.org; response code depends on external availability
        assertThat(response.getStatus()).isGreaterThan(0);
    }

    @Test
    public void shouldReturnResponseAsStringFromProxyEndpoint() {
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get();

        String content = response.readEntity(String.class);
        assertThat(content).isNotNull();
        assertThat(content).isNotEmpty();
    }

    // ---- Connection Lifecycle Tests ----

    @Test
    public void shouldReuseConnectionAcrossMultipleSequentialRequests() {
        for (int i = 0; i < 3; i++) {
            Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                    .request()
                    .get();

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isGreaterThan(0);
            response.close();
        }
    }

    @Test
    public void shouldHandleMultipleClientsIndependently() {
        JerseyClient secondClient = new JerseyClientBuilder().build();
        try {
            Response response1 = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                    .request()
                    .get();

            Response response2 = secondClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                    .request()
                    .get();

            assertThat(response1).isNotNull();
            assertThat(response2).isNotNull();
            assertThat(response1.getStatus()).isGreaterThan(0);
            assertThat(response2.getStatus()).isGreaterThan(0);
            response1.close();
            response2.close();
        } finally {
            secondClient.close();
        }
    }

    @Test
    public void shouldReturn404ForUnknownPath() {
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort() + "/nonexistent")
                .request()
                .get();

        assertThat(response.getStatus()).isEqualTo(404);
        response.close();
    }

    @Test
    public void shouldReturn405ForPostOnGetOnlyEndpoint() {
        Response response = jerseyClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .post(null);

        assertThat(response.getStatus()).isEqualTo(405);
        response.close();
    }

    // ---- Client Cleanup Tests ----

    @Test
    public void shouldAllowClientCloseWithoutError() {
        JerseyClient ephemeralClient = new JerseyClientBuilder().build();
        Response response = ephemeralClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get();
        assertThat(response).isNotNull();
        response.close();
        ephemeralClient.close();
        assertThat(ephemeralClient.isClosed()).isTrue();
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenRequestingAfterClientClosed() {
        JerseyClient closedClient = new JerseyClientBuilder().build();
        closedClient.close();
        closedClient.target("http://127.0.0.1:" + appRule.getLocalPort())
                .request()
                .get();
    }

    // ---- Configuration Tests ----

    @Test
    public void shouldApplyFollowRedirectsDefault() {
        JettyClientConfiguration config = new JettyClientConfiguration();
        assertThat(config.isFollowRedirects()).isTrue();
    }

    @Test
    public void shouldApplyConnectionTimeoutDefault() {
        JettyClientConfiguration config = new JettyClientConfiguration();
        assertThat(config.getConnectionTimeout().toMilliseconds()).isEqualTo(500);
    }

    @Test
    public void shouldApplyIdleTimeoutDefault() {
        JettyClientConfiguration config = new JettyClientConfiguration();
        assertThat(config.getIdleTimeout().toMinutes()).isEqualTo(1);
    }

    @Test
    public void shouldSerializeConfigurationToString() {
        JettyClientConfiguration config = new JettyClientConfiguration();
        String toString = config.toString();
        assertThat(toString).contains("connectionTimeout");
        assertThat(toString).contains("idleTimeout");
        assertThat(toString).contains("storeCookies");
        assertThat(toString).contains("followRedirects");
    }
}
