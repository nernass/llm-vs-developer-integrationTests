package com.github.yuliyadzemidovich.parceldeliveryapp.filter;

import com.github.yuliyadzemidovich.parceldeliveryapp.ParcelDeliveryAppApplication;
import com.github.yuliyadzemidovich.parceldeliveryapp.dto.OrderDto;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.Role;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = ParcelDeliveryAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Profile("local")
@AutoConfigureTestDatabase
class claudeIT_2 {

    @LocalServerPort
    private int port;

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + contextPath;
        userRepository.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    // --- Public endpoint tests ---

    @Test
    void shouldReturn200ForActuatorHealthWithoutAuth() {
        String url = baseUrl + PATH_ACTUATOR_HEALTH;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // --- No auth header tests ---

    @Test
    void shouldReturn401WhenNoAuthorizationHeader() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, String.class);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // --- Invalid JWT tests ---

    @Test
    void shouldReturn401WhenBearerTokenIsGarbage() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + "not.a.valid.jwt");
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, String.class);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturn401WhenAuthorizationHeaderMissingBearerPrefix() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        String validToken = jwtService.generateToken("someone@email.com");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, validToken); // no "Bearer " prefix
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, String.class);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturn401WhenAuthorizationHeaderIsEmpty() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, "");
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, String.class);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // --- Valid JWT but user not in DB ---

    @Test
    void shouldReturn401WhenJwtValidButUserNotInDatabase() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        String validToken = jwtService.generateToken("nonexistent@email.com");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, String.class);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // --- Valid JWT and user exists in DB ---

    @Test
    void shouldReturn200WhenJwtValidAndUserExistsInDatabase() {
        // Seed user into real H2 database
        User user = new User();
        user.setName("IntegrationTestUser");
        user.setEmail("integrationuser@email.com");
        user.setPassword("{bcrypt}$2a$10$QLJbKBmqJdXVN.4EJ0XGLukc5calPzAIR4KkbttILE1twXGXZ1dSa");
        user.setRole(Role.ROLE_USER);
        userRepository.save(user);

        String validToken = jwtService.generateToken("integrationuser@email.com");
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void shouldReturn403ForSuperAdminOnUserOnlyEndpoint() {
        User superAdmin = new User();
        superAdmin.setName("super admin");
        superAdmin.setEmail("sadmin@example.com");
        superAdmin.setPassword("{bcrypt}$2a$10$QLJbKBmqJdXVN.4EJ0XGLukc5calPzAIR4KkbttILE1twXGXZ1dSa");
        superAdmin.setRole(Role.ROLE_SUPER_ADMIN);
        userRepository.save(superAdmin);

        String validToken = jwtService.generateToken("sadmin@example.com");
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        assertNotNull(response);
        // Super admin has ROLE_SUPER_ADMIN; getUserOrders requires hasRole('USER') so expect 403
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
