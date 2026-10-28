
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class RamlSpecificationRuleEnhancedIntegrationTest {
    private static final int MOCK_SERVER_PORT = 5000;
    private static final String BASE_URL = "http://localhost:" + MOCK_SERVER_PORT;
    private static final String VALID_BASIC_AUTH = "Basic TOKEN1234";

    @Rule
    public MockServerRule server = new MockServerRule(this, MOCK_SERVER_PORT);

    @ClassRule
    public static final RamlSpecificationsRule SPECIFICATIONS = new RamlSpecificationsRule()
            .withSpecifications(
                    new ClassPathSpecification("my-service", "apispecs-test/apispecs.raml")
            );

    private MockServerClient mockClient;
    private Client client;

    @Before
    public void setUp() {
        mockClient = new MockServerClient("localhost", MOCK_SERVER_PORT);
        client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    public void shouldCorrectlyVerifyMockServerExpectationsAgainstRamlSpecification() throws IOException {
        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withBody("Hello John!")
        );

        String responseBody = client.target(BASE_URL + "/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .isEqualTo(fixture("fixtures/expected-obey-errors-get-hello-john.txt"));
    }

    @Test
    public void shouldAllowSafeErrorResponsesWhenRequestStillMatchesRaml() {
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

        Response response = client.target(BASE_URL + "/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();
        try {
            assertThat(response.getStatus()).isEqualTo(503);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldPassAllRamlRequirementsForTextGreeting() {
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
                                new Header("ttl", "1m")
                        )
                        .withBody("Hello John!")
        );

        String responseBody = client.target(BASE_URL + "/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldPassRamlRequirementsForValidJsonGreetingUpdateWhenBasicAuthenticationIsUsed() throws IOException, JSONException {
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

        String actualResponse = client.target(BASE_URL + "/hello/John/greetings")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH)
                .put(Entity.entity(requestBody, MediaType.APPLICATION_JSON), String.class);

        JSONAssert.assertEquals(responseBody, actualResponse, false);

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldRaiseErrorsWhenJsonBodiesAreIncorrect() throws IOException, JSONException {
        String requestBody = fixture("fixtures/incorrect-greeting-request.json");
        String responseBody = fixture("fixtures/incorrect-greetings-response.json");

        mockClient.when(
                request()
                        .withPath("/hello/Sarah/greetings")
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

        String actualResponse = client.target(BASE_URL + "/hello/Sarah/greetings")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, VALID_BASIC_AUTH)
                .put(Entity.entity(requestBody, MediaType.APPLICATION_JSON), String.class);

        JSONAssert.assertEquals(responseBody, actualResponse, false);

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .isEqualTo(fixture("fixtures/expected-obey-errors-put-greeting.txt"));
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

        String responseBody = client.target(BASE_URL + "/goodbye/John")
                .request()
                .get(String.class);

        assertThat(responseBody).isEqualTo("Goodbye John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ expectation ] [ GET ] [ /goodbye/John ]")
                .contains("[ request ] No resource matching for request found.")
                .contains("[ response ] No valid response specification exists for expectation.");
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

        Response response = client.target(BASE_URL + "/hello/John")
                .request()
                .post(Entity.text("ignored"));
        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ expectation ] [ POST ] [ /hello/John ]")
                .contains("[ request ] Resource for does not support method.")
                .contains("[ response ] No valid response specification exists for expectation.");
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

        Response response = client.target(BASE_URL + "/hello/John")
                .queryParam("my-token", "12345")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();
        try {
            assertThat(response.getStatus()).isEqualTo(503);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.STRICT);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ expectation ] [ GET ] [ /hello/John ]")
                .contains("[ response ] No valid response specification exists for expectation.");
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

        Response response = client.target(BASE_URL + "/hello/John")
                .request()
                .head();
        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        RamlSpecification.Result defaultResult = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);
        assertTrue(defaultResult.getFormattedErrorMessage(), defaultResult.isValid());

        RamlSpecification.Result headCheckedResult = SPECIFICATIONS.get("my-service").obeyedBy(mockClient, "HEAD");

        assertFalse(headCheckedResult.isValid());
        assertThat(headCheckedResult.getFormattedErrorMessage())
                .contains("[ expectation ] [ HEAD ] [ /hello/John ]")
                .contains("[ request ] Resource for does not support method.")
                .contains("[ response ] No valid response specification exists for expectation.");
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
                                new Header("ttl", "1m")
                        )
                        .withBody("Hello John!")
        );

        String responseBody = client.target(BASE_URL + "/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer TOKEN1234")
                .get(String.class);

        assertThat(responseBody).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ expectation ] [ GET ] [ /hello/John ]")
                .contains("[ security ] [ header ]")
                .contains("Value of [ Bearer TOKEN1234 ] does not meet API requirements.");
    }
}