package net.ozwolf.mockserver.raml;

import net.ozwolf.mockserver.raml.specification.ClassPathSpecification;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.json.JSONException;
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

public class RamlSpecificationRuleIntegrationTest {
    @Rule
    public MockServerRule server = new MockServerRule(this, 5000);

    @ClassRule
    public final static RamlSpecificationsRule SPECIFICATIONS = new RamlSpecificationsRule()
            .withSpecifications(
                    new ClassPathSpecification("my-service", "apispecs-test/apispecs.raml")
            );

    @Test
    public void shouldPassRamlRequirementsWhenBasicAuthenticationIsUsed() throws IOException {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withHeader(new Header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "1m")
                        )
                        .withBody("Hello John!")
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        String response = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .get(String.class);
        assertThat(response).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldPassRamlRequirementsForValidJsonGreetingUpdate() throws IOException, JSONException {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John/greetings")
                        .withMethod("PUT")
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON),
                                new Header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON),
                                new Header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                        )
                        .withBody(fixture("fixtures/valid-greeting-request.json"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                        )
                        .withBody(fixture("fixtures/valid-greetings-response.json"))
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        String response = client.target("http://localhost:5000/hello/John/greetings")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .put(Entity.entity(fixture("fixtures/valid-greeting-request.json"), "application/json"), String.class);
        JSONAssert.assertEquals(fixture("fixtures/valid-greetings-response.json"), response, false);

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldReportMissingResourceWhenExpectationPathIsNotInSpecification() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/goodbye/John")
                        .withMethod("GET")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withBody("Goodbye John!")
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        String response = client.target("http://localhost:5000/goodbye/John")
                .request()
                .get(String.class);
        assertThat(response).isEqualTo("Goodbye John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No resource matching for request found"));
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No valid response specification exists for expectation"));
    }

    @Test
    public void shouldReportUnsupportedMethodWhenResourceDoesNotDefineIt() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("POST")
                        .withBody("Test body")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withBody("OK")
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        String response = client.target("http://localhost:5000/hello/John")
                .request()
                .post(Entity.text("Test body"), String.class);
        assertThat(response).isEqualTo("OK");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("Resource for does not support method"));
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No valid response specification exists for expectation"));
    }

    @Test
    public void shouldRejectUnexpectedResponseCodeWhenStrictModeIsUsed() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(503)
                        .withBody("Service Unavailable")
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        Response response = client.target("http://localhost:5000/hello/John")
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
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No valid response specification exists for expectation"));
    }

    @Test
    public void shouldAllow503WhenSafeErrorsModeIsUsed() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(503)
                        .withBody("Service Unavailable")
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();
        try {
            assertThat(response.getStatus()).isEqualTo(503);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.SAFE_ERRORS);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldIgnoreHeadExpectationsUntilHeadValidationIsExplicitlyRequested() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("HEAD")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .head();
        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        // HEAD is not in default methods, so validation should ignore it
        RamlSpecification.Result result = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldValidateHeadExpectationsWhenHeadValidationIsExplicitlyRequested() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("HEAD")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .head();
        try {
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            response.close();
        }

        // HEAD explicitly included in validation
        RamlSpecification.Result result = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, "HEAD");

        assertFalse(result.isValid());
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No valid response specification exists for expectation"));
    }

    @Test
    public void shouldReportSecurityValidationErrorsWhenAuthorizationHeaderDoesNotMatchPattern() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
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

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        String response = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer TOKEN1234")
                .get(String.class);
        assertThat(response).isEqualTo("Hello John!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("[ security ] [ header ] [ Basic authentication ] Value of [ Bearer TOKEN1234 ] does not meet API requirements"));
    }
}