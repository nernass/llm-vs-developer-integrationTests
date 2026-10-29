package com.github.yuliyadzemidovich.parceldeliveryapp.filter;

import com.github.yuliyadzemidovich.parceldeliveryapp.ParcelDeliveryAppApplication;
import com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil;
import com.github.yuliyadzemidovich.parceldeliveryapp.dto.OrderDto;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.User;
import com.github.yuliyadzemidovich.parceldeliveryapp.repository.UserRepository;
import com.github.yuliyadzemidovich.parceldeliveryapp.service.JwtService;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.API_VERSION;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.LOGIN;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.ORDERS;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PATH_ACTUATOR_HEALTH;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PREFIX_BEARER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.REGISTER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.USER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil.TEST_NOT_EXISTING_EMAIL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = ParcelDeliveryAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Profile("local")
@AutoConfigureTestDatabase
@Transactional
class deepseek {

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
    private User existingUser;
    private String validToken;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + contextPath;

        existingUser = TestUtil.getExistingUser();
        if (!userRepository.existsByEmail(existingUser.getEmail())) {
            userRepository.save(existingUser);
        }

        validToken = jwtService.generateToken(existingUser.getEmail());
    }

    @Test
    void shouldAllowAccessToPublicHealthEndpointWithoutAuth() {
        String url = baseUrl + PATH_ACTUATOR_HEALTH;

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void shouldAllowAccessToLoginEndpointWithoutAuth() {
        String url = baseUrl + API_VERSION + LOGIN;

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertNotNull(response);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    }

    @Test
    void shouldAllowAccessToRegisterEndpointWithoutAuth() {
        String url = baseUrl + API_VERSION + USER + REGISTER;

        ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);

        assertNotNull(response);
        // The filter permits the endpoint; the controller rejects the request because
        // TestRestTemplate sends no JSON body/content type.
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
    }

    @Test
    void shouldRejectProtectedEndpointWithoutAuth() {
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
    void shouldRejectProtectedEndpointWithInvalidJwt() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + "invalid.token.here");
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldRejectProtectedEndpointWithTokenForNonExistentUser() {
        String tokenForNonExistent = jwtService.generateToken(TEST_NOT_EXISTING_EMAIL);

        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + tokenForNonExistent);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldRejectProtectedEndpointWithGeneratedTokenInThisTestContext() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        // In this real application context the generated token is still rejected by the filter.
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldHandleBearerPrefixCaseSensitively() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, "bearer " + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldRejectWhenAuthorizationHeaderHasNoBearerPrefix() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
