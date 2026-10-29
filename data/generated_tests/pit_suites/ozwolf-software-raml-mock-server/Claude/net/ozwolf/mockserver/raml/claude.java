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
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class claude {
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
    public void shouldPassWhenCustomTokenSecurityIsUsedViaQueryParameter() {
        mockClient.when(
                request()
                        .withPath("/hello/Sally")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "my-secret-token-value"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "30s")
                        )
                        .withBody("Hello Sally!")
        );

        String responseBody = client.target("http://localhost:5000/hello/Sally")
                .queryParam("my-token", "my-secret-token-value")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello Sally!");
        assertValid(SPECIFICATIONS.get("my-service").obeyedBy(mockClient));
    }

    @Test
    public void shouldPassWhenGetGreetingsSubResourceReturnsValidJsonResponse() throws IOException {
        String responseJson = fixture("apispecs-test/examples/greetings-response.json");

        mockClient.when(
                request()
                        .withPath("/hello/John/greetings")
                        .withMethod("GET")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON))
                        .withBody(responseJson)
        );

        String actual = client.target("http://localhost:5000/hello/John/greetings")
                .request()
                .get(String.class);

        assertThat(actual).isEqualTo(responseJson);
        assertValid(SPECIFICATIONS.get("my-service").obeyedBy(mockClient));
    }

    @Test
    public void shouldReportDeleteMethodNotSupportedByResource() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("DELETE")
        ).respond(
                response()
                        .withStatusCode(204)
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .delete();

        try {
            assertThat(response.getStatus()).isEqualTo(204);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertInvalid(result,
                "[ request ] Resource for does not support method.",
                "[ response ] No valid response specification exists for expectation.");
    }

    @Test
    public void shouldAllowUndefined401ResponseCodeUnderAllErrorsMode() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(401)
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(401);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient, ResponseObeyMode.ALL_ERRORS);
        assertValid(result);
    }

    @Test
    public void shouldReportUriParameterViolationWhenNameDoesNotMatchPattern() {
        mockClient.when(
                request()
                        .withPath("/hello/Bob")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "30s")
                        )
                        .withBody("Hello Bob!")
        );

        String responseBody = client.target("http://localhost:5000/hello/Bob")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello Bob!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertInvalid(result,
                "[ request ] [ uri ] [ name ] Value of [ Bob ] does not meet API requirements.");
    }

    @Test
    public void shouldPassWhenRamlDefined404ResponseIsReturned() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(404)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withBody("Could not find [ John ] to greet.")
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(404);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);
        assertValid(result);
    }

    @Test
    public void shouldReportResponseJsonBodySchemaViolationForGetEndpoint() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON),
                                new Header("ttl", "30s")
                        )
                        .withBody("{\"greeting\": \"Hello John!\"}")
        );

        String responseBody = client.target("http://localhost:5000/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .get(String.class);

        assertThat(responseBody).contains("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);
        assertInvalid(result, "[ response ] [ body ] object has missing required properties ([\"name\"])");
    }

    @Test
    public void shouldReportUndefined201ResponseCodeEvenUnderSafeErrorsMode() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(201)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withBody("Hello John!")
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(201);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);
        assertInvalid(result, "[ response ] No valid response specification exists for expectation.");
    }

    @Test
    public void shouldOnlyReportErrorsForInvalidExpectationsWhenMultipleExist() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "30s")
                        )
                        .withBody("Hello John!")
        );

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("POST")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withBody("Created")
        );

        client.target("http://localhost:5000/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        Response postResponse = client.target("http://localhost:5000/hello/John")
                .request()
                .post(Entity.text("ignored"));
        postResponse.close();

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMethod()).isEqualTo("POST");
        assertThat(result.getErrors().get(0).getMessages()).contains("[ request ] Resource for does not support method.");
    }

    private void assertValid(RamlSpecification.Result result) {
        assertThat(result.isValid()).as(result.getFormattedErrorMessage()).isTrue();
    }

    private void assertInvalid(RamlSpecification.Result result, String... messages) {
        assertThat(result.isValid()).isFalse();
        assertThat(result.getFormattedErrorMessage()).contains(messages);
    }
}
