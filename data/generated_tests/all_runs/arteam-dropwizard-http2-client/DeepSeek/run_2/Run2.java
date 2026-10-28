package com.github.arteam.dropwizard.http2.client;

import com.github.arteam.dropwizard.http2.client.names.NameStrategies;
import com.github.arteam.dropwizard.http2.client.names.NameStrategy;
import com.github.arteam.dropwizard.http2.client.transport.ClientTransportFactory;
import com.github.arteam.dropwizard.http2.client.transport.Http2ClientTransportFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.common.collect.Lists;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransport;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for HTTP/2 proxy using a mocked external service (httpbin.org).
 * Replaces the real external call with WireMock to provide deterministic and fast tests.
 */
public class HttpBinProxyIntegrationTest {

    private static final int WIREMOCK_HTTPS_PORT = 0; // random port
    private static final WireMockServer wireMock = new WireMockServer(options().httpsPort(WIREMOCK_HTTPS_PORT));

    private static final int PROXY_IDLE_TIMEOUT_MS = 2000;

    private static GoLangProxyConfiguration createConfiguration(int wireMockPort) {
        GoLangProxyConfiguration config = new GoLangProxyConfiguration();
        JettyClientConfiguration clientConfig = new JettyClientConfiguration();
        // Short timeouts for fast tests
        clientConfig.setIdleTimeout(io.dropwizard.util.Duration.milliseconds(PROXY_IDLE_TIMEOUT_MS));
        clientConfig.setConnectionTimeout(io.dropwizard.util.Duration.milliseconds(1000));
        clientConfig.setFollowRedirects(false);
        clientConfig.setStoreCookies(false);
        // Use custom transport factory that redirects httpbin.org to local WireMock
        clientConfig.setConnectionFactoryBuilder(
                new WireMockAwareClientTransportFactory(wireMockPort)
        );
        config.setH2Client(clientConfig);
        return config;
    }

    @ClassRule
    public static final DropwizardAppRule<GoLangProxyConfiguration> APP_RULE =
            new DropwizardAppRule<>(GoLangProxyApplication.class, createConfiguration(wireMock.httpsPort()));

    private JerseyClient jerseyClient;

    @Before
    public void setUp() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"Host\": \"httpbin.org\", \"origin\": \"127.0.0.1\"}")));
        jerseyClient = new JerseyClientBuilder().build();
    }

    @After
    public void tearDown() {
        if (jerseyClient != null) {
            jerseyClient.close();
        }
    }

    @Test
    public void shouldProxyRequestSuccessfully() {
        String response = jerseyClient.target("http://127.0.0.1:" + APP_RULE.getLocalPort())
                .request()
                .get()
                .readEntity(String.class);

        assertThat(response).contains("\"Host\": \"httpbin.org\"");
        assertThat(response).contains("\"origin\": \"127.0.0.1\"");
    }

    @Test
    public void shouldReturnUpstreamErrorWhenExternalServiceFails() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        String response = jerseyClient.target("http://127.0.0.1:" + APP_RULE.getLocalPort())
                .request()
                .get()
                .readEntity(String.class);

        assertThat(response).isEqualTo("Internal Server Error");
    }

    @Test
    public void shouldHandleTimeoutWhenExternalServiceIsSlow() {
        // Override default stub with a delayed response (longer than idle timeout)
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo("/get"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(PROXY_IDLE_TIMEOUT_MS + 1000)
                        .withBody("{}")));

        assertThatThrownBy(() -> jerseyClient.target("http://127.0.0.1:" + APP_RULE.getLocalPort())
                .request()
                .get()
                .readEntity(String.class))
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    public void shouldReuseConnectionsAndReopenAfterIdle() throws Exception {
        // Use short idle timeout to force connection re‑opening
        long start = System.currentTimeMillis();
        long now = start;
        int attempt = 1;
        while (now - start < 5000) { // shorter duration for a fast test
            String response = jerseyClient.target("http://127.0.0.1:" + APP_RULE.getLocalPort())
                    .request()
                    .get()
                    .readEntity(String.class);
            assertThat(response).contains("\"Host\": \"httpbin.org\"");

            now = System.currentTimeMillis();
            // After every 5 attempts, sleep longer than idle timeout to force reconnection
            if (attempt++ % 5 == 0) {
                Thread.sleep(PROXY_IDLE_TIMEOUT_MS + 500);
                attempt = 1;
            }
        }
    }

    /**
     * Custom {@link ClientTransportFactory} that:
     * - Resolves {@code httpbin.org} to {@code localhost} on the WireMock HTTPS port.
     * - Disables SSL hostname verification and trusts all certificates.
     */
    public static class WireMockAwareClientTransportFactory extends Http2ClientTransportFactory {

        private final int wireMockPort;

        public WireMockAwareClientTransportFactory(int wireMockPort) {
            this.wireMockPort = wireMockPort;
        }

        @Override
        public SslContextFactory sslContextFactory() {
            SslContextFactory.Client sslFactory = new SslContextFactory.Client(true);
            // Disable hostname verification and trust all for test purposes
            sslFactory.setHostnameVerifier((host, session) -> true);
            sslFactory.setTrustAll(true);
            return sslFactory;
        }

        @Override
        public HttpClientTransport httpClientTransport() {
            // Wrap the default HTTP/2 transport to inject a custom socket address resolver
            HTTP2Client http2Client = new HTTP2Client();
            HttpClientTransportOverHTTP2 transport = new HttpClientTransportOverHTTP2(http2Client) {
                @Override
                public HttpClient newHttpClient(Executor executor, SslContextFactory sslContextFactory) {
                    HttpClient client = super.newHttpClient(executor, sslContextFactory);
                    // Override DNS resolution: map httpbin.org:443 to localhost:wireMockPort
                    client.setSocketAddressResolver((host, port) -> {
                        if ("httpbin.org".equals(host) && port == 443) {
                            return Collections.singletonList(
                                    new InetSocketAddress("127.0.0.1", wireMockPort)
                            );
                        }
                        try {
                            return super.resolve(host, port);
                        } catch (UnknownHostException e) {
                            return Lists.newArrayList();
                        }
                    });
                    return client;
                }
            };
            return transport;
        }
    }

    static {
        // Start WireMock with HTTPS support before the application rule is initialised
        wireMock.start();
    }
}