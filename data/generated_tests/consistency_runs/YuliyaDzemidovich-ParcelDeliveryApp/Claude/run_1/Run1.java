package com.github.yuliyadzemidovich.parceldeliveryapp.filter;

import com.github.yuliyadzemidovich.parceldeliveryapp.ParcelDeliveryAppApplication;
import com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil;
import com.github.yuliyadzemidovich.parceldeliveryapp.dto.OrderDto;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.User;
import com.github.yuliyadzemidovich.parceldeliveryapp.repository.UserRepository;
import com.github.yuliyadzemidovich.parceldeliveryapp.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.API_VERSION;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.ORDERS;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PATH_ACTUATOR_HEALTH;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PREFIX_BEARER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.USER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil.TEST_NOT_EXISTING_EMAIL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test covering JWTAuthorizationFilter -> JwtService -> UserRepository -> OrderController.
 * <p>
 * Mocking policy: none of the involved components (JwtService, UserRepository) are external
 * third-party integrations, so nothing is mocked with @MockBean. The real Spring Security filter
 * chain, the real JwtService (genuine signed/verified JWTs) and the real UserRepository backed by
 * an in-memory H2 database (via @AutoConfigureTestDatabase) are exercised end-to-end through a
 * TestRestTemplate hitting the embedded, randomly-ported HTTP server.
 * <p>
 * Database reset strategy: because the HTTP request is served on the embedded container's own
 * thread/connection (separate from the test thread), wrapping the test class in @Transactional
 * would make data seeded in @BeforeEach invisible to the server when it services the request
 * (it would still be uncommitted). Instead, state is reset explicitly and reliably via
 * userRepo.deleteAll() in @AfterEach, with fresh seed data inserted per test in @BeforeEach/tests.
 */
@SpringBootTest(classes = ParcelDeliveryAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Profile("local") // include context-path var
@AutoConfigureTestDatabase // use in-memory H2 instead of init Datasource bean with connection to the real database
class JWTAuthorizationFilterIntegrationTest {

    private static final String INVALID_JWT = "invalid.jwt.token";

    @LocalServerPort
    private int port;

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @Autowired
    TestRestTemplate restTemplate;
    @Autowired
    JwtService jwtService;
    @Autowired
    UserRepository userRepo;

    private String baseUrl;

    @BeforeEach
    void init() {
        baseUrl = "http://localhost:" + port + contextPath;
    }

    @AfterEach
    void cleanUp() {
        // real, committed cleanup so no user data bleeds over into the next test
        userRepo.deleteAll();
    }

    @Test
    void allowedNoAuthRequest_actuatorHealthAccessibleWithoutAuthentication() {
        String url = baseUrl + PATH_ACTUATOR_HEALTH;

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void forbiddenNoAuthRequest_protectedEndpointReturns401WithoutAuthorizationHeader() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void invalidAuthJwtRequest_protectedEndpointReturns401WithMalformedJwt() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + INVALID_JWT);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void unknownUserWithJwtRequest_protectedEndpointReturns401WhenUserEmailNotInRepository() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        // real JwtService issues a genuinely valid, signed token for an email that is never persisted
        assertTrue(!userRepo.existsByEmail(TEST_NOT_EXISTING_EMAIL));
        String validTokenForUnknownUser = jwtService.generateToken(TEST_NOT_EXISTING_EMAIL);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validTokenForUnknownUser);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void allowedWithJwtRequest_protectedEndpointReturns200WhenJwtValidAndUserExists() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        // seed a real user in the H2 database
        User existingUser = TestUtil.getUser1();
        userRepo.save(existingUser);
        String existingEmail = existingUser.getEmail();
        assertTrue(userRepo.existsByEmail(existingEmail));

        // real JwtService issues a genuinely valid, signed token for the persisted user's email
        String validToken = jwtService.generateToken(existingEmail);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void unauthorizedRoleRequest_protectedEndpointReturns403WhenValidJwtButInsufficientRole() {
        String url = baseUrl + API_VERSION + com.github.yuliyadzemidovich.parceldeliveryapp.Constants.ADMIN + ORDERS;

        // seed a real user with ROLE_USER trying to hit an admin-only endpoint
        User existingUser = TestUtil.getUser1();
        userRepo.save(existingUser);
        String existingEmail = existingUser.getEmail();
        String validToken = jwtService.generateToken(existingEmail);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
