package net.ozwolf.mockserver.raml;

import net.ozwolf.mockserver.raml.specification.ClassPathSpecification;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockserver.client.server.MockServerClient;
import org.mockserver.junit.MockServerRule;
import org.mockserver.model.Header;
import org.mockserver.model.Parameter;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

import static net.ozwolf.mockserver.raml.util.Fixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class gemini {

    @Rule
    public MockServerRule server = new MockServerRule(this, 5000);

    @ClassRule
    public final static RamlSpecificationsRule SPECIFICATIONS = new RamlSpecificationsRule()
            .withSpecifications(new ClassPathSpecification("my-service", "apispecs-test/apispecs.raml"));

    private MockServerClient mockClient;
    private Client client;

    @Before
    public void setUp() {
        this.mockClient = new MockServerClient("localhost", 5000);
        this.client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    public void shouldReportErrorsWhenAcceptHeaderDoesNotMatchPattern() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.AUTHORIZATION, "Basic d3hwMDAxQGFzaXM6dGVzdGluZw=="))
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "1m")
                        )
                        .withBody("Hello John!")
        );

        String responseBody = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Basic d3hwMDAxQGFzaXM6dGVzdGluZw==")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage()).contains("[ request ] [ header ] [ Accept ] Value of [ application/xml ] does not meet API requirements.");
    }

    @Test
    public void shouldPassWhenReturningValid404Response() {
        mockClient.when(
                request()
                        .withPath("/hello/John/greetings")
                        .withMethod("GET")
        ).respond(
                response()
                        .withStatusCode(404)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withBody("Could not find [ Steven ] to greet.")
        );

        Response response = client.target("http://localhost:5000/hello/John/greetings")
                .request()
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(response.readEntity(String.class)).isEqualTo("Could not find [ Steven ] to greet.");
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient, ResponseObeyMode.STRICT);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    /*@Test
    public void shouldPassWhenMyTokenIsProvidedAsHeader() {
        mockClient.when(
                request()
                        .withPath("/hello/Sally")
                        .withMethod("GET")
                        .withHeader(new Header("my-token", "12345"))
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "1m")
                        )
                        .withBody("Hello Sally!")
        );

        String responseBody = client.target("http://localhost:5000/hello/Sally")
                .request()
                .header("my-token", "12345")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello Sally!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }*/
    @Test
    public void shouldRejectPostMethodOnHelloEndpoint() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("POST")
                        .withHeader(new Header(HttpHeaders.AUTHORIZATION, "Basic d3hwMDAxQGFzaXM6dGVzdGluZw=="))
        ).respond(
                response()
                        .withStatusCode(201)
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Basic d3hwMDAxQGFzaXM6dGVzdGluZw==")
                .post(Entity.text("ignored"));

        try {
            assertThat(response.getStatus()).isEqualTo(201);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage()).contains("[ request ] Resource for does not support method.");
        assertThat(result.getFormattedErrorMessage()).contains("[ response ] No valid response specification exists for expectation.");
    }
}
