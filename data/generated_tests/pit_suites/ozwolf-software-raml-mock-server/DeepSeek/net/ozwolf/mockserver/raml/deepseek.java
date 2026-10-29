package net.ozwolf.mockserver.raml;

import net.ozwolf.mockserver.raml.specification.ClassPathSpecification;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockserver.client.server.MockServerClient;
import org.mockserver.junit.MockServerRule;
import org.mockserver.model.Header;
import org.mockserver.model.Parameter;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class deepseek {

    @Rule
    public MockServerRule server = new MockServerRule(this, 5000);

    @ClassRule
    public final static RamlSpecificationsRule SPECIFICATIONS = new RamlSpecificationsRule()
            .withSpecifications(
                    new ClassPathSpecification("my-service", "apispecs-test/apispecs.raml")
            );

    private MockServerClient mockClient;

    @After
    public void resetMockServer() {
        if (mockClient != null) {
            mockClient.reset();
        }
    }

    @Test
    public void shouldFailWhenMissingRequiredSecurityCredentials() {
        mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withHeader(new Header("ttl", "60"))
                        .withBody("Hello John!")
        );

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("Missing required security credentials");
    }

    @Test
    public void shouldFailWhenMissingRequiredResponseHeader() {
        mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withBody("Hello John!")
        );

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ header ] [ ttl ] Parameter is compulsory");
    }

    @Test
    public void shouldPassWithStrictResponseCodesWhenExplicitlyAllowed() {
        mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withHeader(new Header("ttl", "60"))
                        .withBody("Hello John!")
        );

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.STRICT);

        assertTrue(result.getFormattedErrorMessage(), result.isValid());
    }

    @Test
    public void shouldFailWhenRequiredResponseHeaderMissingInStrictMode() {
        mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withBody("Hello John!")
        );

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.STRICT);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("[ header ] [ ttl ] Parameter is compulsory");
    }

    @Test
    public void shouldFailWhenPathDoesNotMatchAnyRamlResource() {
        mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/unknown/path")
                        .withMethod("GET")
        ).respond(
                response()
                        .withStatusCode(200)
        );

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getFormattedErrorMessage())
                .contains("No resource matching for request found");
    }

    @Test
    public void shouldHandleMultipleExpectationsWithMixedResults() {
        mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("GET")
                        .withHeader(new Header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN))
                        .withQueryStringParameter(new Parameter("my-token", "12345"))
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
                        .withHeader(new Header("ttl", "60"))
                        .withBody("Hello John!")
        );

        mockClient.when(
                request()
                        .withPath("/unknown/path")
                        .withMethod("GET")
        ).respond(
                response()
                        .withStatusCode(200)
        );

        RamlSpecification.Result result = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);

        assertFalse(result.isValid());
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getFormattedErrorMessage())
                .contains("No resource matching for request found");
    }

    @Test
    public void shouldCheckAdditionalMethodsWhenSpecified() {
        mockClient = new MockServerClient("localhost", 5000);

        mockClient.when(
                request()
                        .withPath("/hello/John")
                        .withMethod("HEAD")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader(new Header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN))
        );

        RamlSpecification.Result resultIgnored = SPECIFICATIONS.get("my-service").obeyedBy(mockClient);
        assertTrue(resultIgnored.getFormattedErrorMessage(), resultIgnored.isValid());

        RamlSpecification.Result resultChecked = SPECIFICATIONS.get("my-service")
                .obeyedBy(mockClient, ResponseObeyMode.SAFE_ERRORS, "HEAD");

        assertFalse(resultChecked.isValid());
        assertThat(resultChecked.getFormattedErrorMessage())
                .contains("Resource for does not support method");
    }
}
