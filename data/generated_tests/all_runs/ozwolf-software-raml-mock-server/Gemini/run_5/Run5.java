package net.ozwolf.mockserver.raml;

import net.ozwolf.mockserver.raml.specification.ClassPathSpecification;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.json.JSONException;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockserver.client.server.MockServerClient;
import org.mockserver.junit.MockServerRule;
import org.mockserver.model.Header;
import org.skyscreamer.jsonassert.JSONAssert;

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

public class RamlSpecificationContractIntegrationTest {

    private static final String VALID_BASIC_AUTH = "Basic d3hwMDAxQGFzaXM6dGVzdGluZw==";
    private static final int MOCK_SERVER_PORT = 5000;

    @Rule
    public MockServerRule server = new MockServerRule(this, MOCK_SERVER_PORT);

    @ClassRule
    public final static RamlSpecificationsRule SPECIFICATIONS = new RamlSpecificationsRule()
            .withSpecifications(
                    new ClassPathSpecification("my-service", "apispecs-test/apispecs.raml")
            );

    private MockServerClient mockClient;
    private Client httpClient;

    @Before
    public void setUp() {
        mockClient = new MockServerClient("localhost", MOCK_SERVER_PORT);
        httpClient = new JerseyClientBuilder().withConfig(new ClientConfig()).build();
    }

    @After
    public void tearDown() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    @Test
    public void shouldPassRamlRequirementsWhenBasicAuthenticationIsUsed() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeaders(
                                new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN),
                                new Header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH)
                        )
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "1m")
                        )
                        .withBody("Hello John!")
        );

        String responseBody = httpClient.target("http://localhost:" + MOCK_SERVER_PORT + "/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldPassRamlRequirementsForValidJsonGreetingUpdate() throws IOException, JSONException {
        String requestBody = fixture("apispecs-test/examples/greeting-request.json");
        String responseBody = fixture("apispecs-test/examples/greetings-response.json");

        mockClient.when(
                request()
                        .withPath("/hello/John/greetings")
                        .withMethod("PUT")
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON),
                                new Header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON),
                                new Header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH)
                        )
                        .withBody(requestBody)
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON))
                        .withBody(responseBody)
        );

        String actualResponse = httpClient.target("http://localhost:" + MOCK_SERVER_PORT + "/hello/John/greetings")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH)
                .put(Entity.entity(requestBody, MediaType.APPLICATION_JSON), String.class);

        JSONAssert.assertEquals(responseBody, actualResponse, false);

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
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
                        .withBody("Goodbye John!")
        );

        Response response = httpClient.target("http://localhost:" + MOCK_SERVER_PORT + "/goodbye/John")
                .request()
                .get();
        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ expectation ] [ GET ] [ /goodbye/John ]")
                .contains("No resource matching for request found")
                .contains("No valid response specification exists");
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
                        .withBody("Hello John!")
        );

        Response response = httpClient.target("http://localhost:" + MOCK_SERVER_PORT + "/hello/John")
                .request()
                .post(Entity.text("Hello John!"));
        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ expectation ] [ POST ] [ /hello/John ]")
                .contains("Resource for does not support method")
                .contains("No valid response specification exists");
    }

    @Test
    public void shouldRejectUnexpectedResponseCodeWhenStrictModeIsUsed() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH))
        ).respond(
                response()
                        .withStatusCode(503)
        );

        Response response = httpClient.target("http://localhost:" + MOCK_SERVER_PORT + "/hello/John")
                .request()
                .header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH)
                .get();
        try {
            assertThat(response.getStatus()).isEqualTo(503);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient, ResponseObeyMode.STRICT);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ expectation ] [ GET ] [ /hello/John ]")
                .contains("No valid response specification exists");
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

        Response response = httpClient.target("http://localhost:" + MOCK_SERVER_PORT + "/hello/John")
                .request()
                .head();
        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        RamlSpecification.Result defaultResult = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);
        assertTrue(defaultResult.getFormattedErrorMessage(), defaultResult.isValid());

        RamlSpecification.Result headCheckedResult = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.SAFE_ERRORS, "HEAD");

        assertFalse(headCheckedResult.isValid());
        assertThat(headCheckedResult.getFormattedErrorMessage())
                .contains("[ expectation ] [ HEAD ] [ /hello/John ]")
                .contains("No valid response specification exists");
    }

    @Test
    public void shouldReportSecurityValidationErrorsWhenAuthorizationHeaderDoesNotMatchPattern() {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.AUTHORIZATION, "Bearer TOKEN1234"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "1m")
                        )
                        .withBody("Hello John!")
        );

        String responseBody = httpClient.target("http://localhost:" + MOCK_SERVER_PORT + "/hello/John")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer TOKEN1234")
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ expectation ] [ GET ] [ /hello/John ]")
                .contains("[ security ]")
                .contains("does not meet API requirements");
    }
}