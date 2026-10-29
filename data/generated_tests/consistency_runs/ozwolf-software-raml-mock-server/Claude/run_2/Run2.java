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
import org.skyscreamer.jsonassert.JSONAssert;

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

/**
 * Integration tests covering the full stack of {@link RamlSpecificationsRule}, {@link ClassPathSpecification},
 * {@link RamlSpecification}, MockServer ({@link MockServerRule} / {@link MockServerClient}), a real Jersey HTTP
 * {@link Client} and the internal validation pipeline
 * ({@code ExpectationValidator}, {@code RequestValidator}, {@code ResponseValidator}, {@code RequestSecurityValidator})
 * against the {@code apispecs-test/apispecs.raml} contract, including the {@link ResponseObeyMode} behaviours.
 */
public class RamlContractValidationIntegrationTest {

    @Rule
    public MockServerRule server = new MockServerRule(this, 5000);

    @ClassRule
    public final static RamlSpecificationsRule SPECIFICATIONS = new RamlSpecificationsRule()
            .withSpecifications(
                    new ClassPathSpecification("my-service", "apispecs-test/apispecs.raml")
            );

    private MockServerClient mockClient;
    private Client client;

    @Before
    public void setUp() {
        this.mockClient = new MockServerClient("localhost", 5000);
        this.client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();
    }

    @After
    public void tearDown() {
        if (client != null) client.close();
    }

    @Test
    public void shouldPassRamlRequirementsWhenValidSecurityCredentialsAreProvided() {
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

        String responseBody = client.target("http://localhost:5000/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertValid(result);
    }

    @Test
    public void shouldPassRamlRequirementsForValidJsonGreetingUpdate() throws IOException {
        String requestBody = fixture("apispecs-test/examples/greeting-request.json");
        String responseBody = fixture("apispecs-test/examples/greetings-response.json");

        mockClient.when(
                request()
                        .withPath("/hello/John/greetings")
                        .withMethod("PUT")
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON),
                                new Header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON),
                                new Header(HttpHeaders.AUTHORIZATION, "Basic TOKEN1234")
                        )
                        .withBody(requestBody)
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON))
                        .withBody(responseBody)
        );

        String actual = client.target("http://localhost:5000/hello/John/greetings")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Basic TOKEN1234")
                .put(Entity.entity(requestBody, MediaType.APPLICATION_JSON), String.class);

        JSONAssert.assertEquals(responseBody, actual, false);

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertValid(result);
    }

    @Test
    public void shouldReportMissingResourceWhenExpectationPathIsNotInSpecification() {
        mockClient.when(
                request()
                        .withPath("/goodbye/John")
                        .withMethod("GET")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withBody("Goodbye John!")
        );

        String responseBody = client.target("http://localhost:5000/goodbye/John")
                .request()
                .get(String.class);

        assertThat(responseBody).isEqualTo("Goodbye John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertInvalid(result,
                "[ request ] No resource matching for request found.",
                "[ response ] No valid response specification exists for expectation.");
    }

    @Test
    public void shouldReportUnsupportedMethodWhenResourceDoesNotDefineIt() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("POST")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withBody("Hello John!")
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .post(Entity.text("ignored"));

        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertInvalid(result,
                "[ request ] Resource for does not support method.",
                "[ response ] No valid response specification exists for expectation.");
    }

    @Test
    public void shouldRejectUnexpectedResponseCodeWhenStrictModeIsUsed() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(503)
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(503);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient, ResponseObeyMode.STRICT);

        assertInvalid(result, "[ response ] No valid response specification exists for expectation.");
    }

    @Test
    public void shouldPassSameResponseUnderSafeErrorsObeyModeDespiteStrictModeRejection() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(503)
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();

        try {
            assertThat(response.getStatus()).isEqualTo(503);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient, ResponseObeyMode.SAFE_ERRORS);

        assertValid(result);
    }

    @Test
    public void shouldIgnoreHeadExpectationsUntilHeadValidationIsExplicitlyRequested() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("HEAD")
        ).respond(
                response()
                        .withStatusCode(200)
        );

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .head();

        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        assertValid(SPECIFICATIONS.get("my-service").obeyedBy(mockClient));

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient, "HEAD");

        assertInvalid(result,
                "[ request ] Resource for does not support method.",
                "[ response ] No valid response specification exists for expectation.");
    }

    @Test
    public void shouldReportSecurityValidationErrorsWhenAuthorizationHeaderDoesNotMatchPattern() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeaders(
                                new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN),
                                new Header(HttpHeaders.AUTHORIZATION, "Bearer TOKEN1234")
                        )
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "30s")
                        )
                        .withBody("Hello John!")
        );

        String responseBody = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer TOKEN1234")
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertInvalid(result, "[ security ] [ header ] [ basic ] Value of [ Bearer TOKEN1234 ] does not meet API requirements.");
    }

    @Test
    public void shouldReportMissingSecurityCredentialsWhenNoAuthenticationIsProvided() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "30s")
                        )
                        .withBody("Hello John!")
        );

        String responseBody = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertInvalid(result, "[ request ] [ security ] Missing required security credentials.");
    }

    @Test
    public void shouldReportUriParameterViolationWhenNameDoesNotMatchRamlPattern() {
        mockClient.when(
                request()
                        .withPath("/hello/Steven")
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
                        .withBody("Hello Steven!")
        );

        String responseBody = client.target("http://localhost:5000/hello/Steven")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello Steven!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertInvalid(result, "[ request ] [ uri ] [ name ] Value of [ Steven ] does not meet API requirements.");
    }

    private void assertValid(RamlSpecification.Result result) {
        assertThat(result.isValid()).as(result.getFormattedErrorMessage()).isTrue();
    }

    private void assertInvalid(RamlSpecification.Result result, String... messages) {
        assertThat(result.isValid()).isFalse();
        assertThat(result.getFormattedErrorMessage()).contains(messages);
    }
}