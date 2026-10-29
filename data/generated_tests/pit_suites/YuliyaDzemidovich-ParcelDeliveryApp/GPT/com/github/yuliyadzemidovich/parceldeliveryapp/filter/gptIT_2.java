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
@AutoConfigureTestDatabase
class gptIT_2 {

    @LocalServerPort
    private int port;

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

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

    @Test
    void shouldAllowHealthEndpointWithoutAuth() {
        String url = baseUrl + PATH_ACTUATOR_HEALTH;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void shouldReturn401WhenNoAuthHeader() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, String.class);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturn401WhenJwtInvalid() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + "invalid.jwt.token");
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, String.class);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturn401WhenUserNotFound() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        String validJwt = jwtService.generateToken("nonexistent@example.com");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validJwt);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, String.class);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturn200WhenJwtValidAndUserExists() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        User user = new User();
        user.setEmail("testuser@example.com");
        user.setName("Test User");
        user.setPassword("password");
        user.setRole(Role.ROLE_USER);
        userRepository.save(user);

        String validJwt = jwtService.generateToken("testuser@example.com");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validJwt);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}