package com.github.yuliyadzemidovich.parceldeliveryapp.filter;

import com.github.yuliyadzemidovich.parceldeliveryapp.ParcelDeliveryAppApplication;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class JWTAuthorizationFilterIntegrationTest {

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

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String baseUrl;
    private String testUserEmail;
    private String testUserPassword;
    private String validJwtToken;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + contextPath;
        testUserEmail = "testuser@example.com";
        testUserPassword = "TestPassword123!";
        
        // Create and save a real user in the database
        User user = new User();
        user.setEmail(testUserEmail);
        user.setPassword(passwordEncoder.encode(testUserPassword));
        user.setFirstName("Test");
        user.setLastName("User");
        user.setRole("USER");
        user.setEnabled(true);
        userRepository.save(user);

        // Generate a real JWT token using the actual JwtService
        validJwtToken = jwtService.generateToken(testUserEmail);
    }

    @Test
    void shouldAllowAccessToPublicEndpointWithoutAuthentication() {
        String url = baseUrl + PATH_ACTUATOR_HEALTH;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenAccessingProtectedEndpointWithoutAuthorizationHeader() {
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
    void shouldReturnUnauthorizedWhenAccessingProtectedEndpointWithInvalidJwtToken() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + "invalid.jwt.token");
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenJwtTokenIsValidButUserDoesNotExist() {
        // Create a token for a user that doesn't exist in the database
        String nonExistentEmail = "nonexistent@example.com";
        String tokenForNonExistentUser = jwtService.generateToken(nonExistentEmail);
        
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + tokenForNonExistentUser);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldAllowAccessToProtectedEndpointWithValidJwtTokenAndExistingUser() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validJwtToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });
        
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenJwtTokenIsMalformed() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + "malformed-token");
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenAuthorizationHeaderHasWrongPrefix() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, "WrongPrefix " + validJwtToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenUserIsDisabled() {
        // Create a disabled user
        String disabledUserEmail = "disabled@example.com";
        User disabledUser = new User();
        disabledUser.setEmail(disabledUserEmail);
        disabledUser.setPassword(passwordEncoder.encode("Password123!"));
        disabledUser.setFirstName("Disabled");
        disabledUser.setLastName("User");
        disabledUser.setRole("USER");
        disabledUser.setEnabled(false);
        userRepository.save(disabledUser);
        
        String tokenForDisabledUser = jwtService.generateToken(disabledUserEmail);
        
        String url = baseUrl + API_VERSION + USER + ORDERS;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + tokenForDisabledUser);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() { });
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}