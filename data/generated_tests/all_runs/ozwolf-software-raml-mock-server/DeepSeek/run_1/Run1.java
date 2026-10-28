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

public class RamlSpecificationIntegrationTest {

    @Rule
    public MockServerRule server = new MockServerRule(this, 5000);

    @ClassRule
    public static final RamlSpecificationsRule SPECIFICATIONS = new RamlSpecificationsRule()
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
                        .withHeaders(
                                new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN),
                                new Header(HttpHeaders.AUTHORIZATION, "Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
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

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        String response = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
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
                                new Header(HttpHeaders.AUTHORIZATION, "Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
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
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
                .put(Entity.entity(fixture("fixtures/valid-greeting-request.json"), MediaType.APPLICATION_JSON), String.class);
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
                .anyMatch(msg -> msg.contains("No valid response specification exists"));
    }

    @Test
    public void shouldReportUnsupportedMethodWhenResourceDoesNotDefineIt() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("POST")
                        .withBody("Some payload")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withBody("Posted!")
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        String response = client.target("http://localhost:5000/hello/John")
                .request()
                .post(Entity.text("Some payload"), String.class);
        assertThat(response).isEqualTo("Posted!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("Resource for does not support method"));
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No valid response specification exists"));
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
                .anyMatch(msg -> msg.contains("No valid response specification exists"));
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
                        .withHeaders(
                                new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN),
                                new Header("ttl", "1m")
                        )
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

        RamlSpecification.Result resultDefault = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);
        assertTrue(resultDefault.getFormattedErrorMessage(), resultDefault.isValid());

        RamlSpecification.Result resultWithHead = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.STRICT, "HEAD");
        assertFalse(resultWithHead.isValid());
        assertThat(resultWithHead.getErrors()).hasSize(1);
        assertThat(resultWithHead.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("Resource for does not support method"));
        assertThat(resultWithHead.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No valid response specification exists"));
    }

    @Test
    public void shouldReportSecurityValidationErrorsWhenAuthorizationHeaderDoesNotMatchPattern() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

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
                .anyMatch(msg -> msg.contains("security") && msg.contains("header") && msg.contains("Authorization"));
    }

    @Test
    public void shouldAllowSafeErrorResponseCodesWhenSafeErrorsModeIsUsed() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(404)
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();
        try {
            assertThat(response.getStatus()).isEqualTo(404);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.SAFE_ERRORS);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldRejectAllErrorResponseCodesWhenStrictModeIsUsed() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(404)
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        Response response = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();
        try {
            assertThat(response.getStatus()).isEqualTo(404);
        } finally {
            response.close();
        }

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.STRICT);

        assertFalse(result.isValid());
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No valid response specification exists"));
    }

    @Test
    public void shouldValidateMultipleExpectationsAndReportAllErrors() {
        MockServerClient mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(503)
        );

        mockClient.when(
                request()
                        .withPath("/goodbye/John")
                        .withMethod("GET")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withBody("Goodbye John!")
        );

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("POST")
                        .withBody("Some payload")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withBody("Posted!")
        );

        Client client = new JerseyClientBuilder().withConfig(new ClientConfig()).build();

        Response response1 = client.target("http://localhost:5000/hello/John")
                .request()
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN)
                .get();
        try {
            assertThat(response1.getStatus()).isEqualTo(503);
        } finally {
            response1.close();
        }

        String response2 = client.target("http://localhost:5000/goodbye/John")
                .request()
                .get(String.class);
        assertThat(response2).isEqualTo("Goodbye John!");

        String response3 = client.target("http://localhost:5000/hello/John")
                .request()
                .post(Entity.text("Some payload"), String.class);
        assertThat(response3).isEqualTo("Posted!");

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient, ResponseObeyMode.STRICT);

        assertFalse(result.isValid());
        assertThat(result.getErrors()).hasSize(3);
        assertThat(result.getErrors().get(0).getMessages())
                .anyMatch(msg -> msg.contains("No valid response specification exists"));
        assertThat(result.getErrors().get(1).getMessages())
                .anyMatch(msg -> msg.contains("No resource matching for request found"));
        assertThat(result.getErrors().get(2).getMessages())
                .anyMatch(msg -> msg.contains("Resource for does not support method"));
    }
}