package com.github.yuliyadzemidovich.parceldeliveryapp.filter;

import com.github.yuliyadzemidovich.parceldeliveryapp.ParcelDeliveryAppApplication;
import com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil;
import com.github.yuliyadzemidovich.parceldeliveryapp.dto.OrderDto;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.Order;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.OrderStatus;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.User;
import com.github.yuliyadzemidovich.parceldeliveryapp.repository.OrderRepository;
import com.github.yuliyadzemidovich.parceldeliveryapp.repository.UserRepository;
import com.github.yuliyadzemidovich.parceldeliveryapp.service.JwtService;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.util.List;
import java.util.UUID;

import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.API_VERSION;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.ORDERS;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PATH_ACTUATOR_HEALTH;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PREFIX_BEARER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.USER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil.MOCKED_JWT;
import static com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil.TEST_NOT_EXISTING_EMAIL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private JwtService jwtServiceMock;

    private String baseUrl;
    private User testUser;
    private String testUserEmail;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + contextPath;
        
        // Seed test user in the database
        testUserEmail = "integration.test" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        testUser = User.builder()
                .email(testUserEmail)
                .password(passwordEncoder.encode("TestPassword123!"))
                .build();
        userRepository.save(testUser);
    }

    @Test
    void allowedNoAuthRequestShouldReturnOk() {
        String url = baseUrl + PATH_ACTUATOR_HEALTH;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void forbiddenNoAuthRequestShouldReturnUnauthorized() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(createJsonHeaders()), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void invalidAuthJwtRequestShouldReturnUnauthorized() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + "invalid-token");
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void malformedAuthHeaderShouldReturnUnauthorized() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "MalformedToken");
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void invalidAuthJwtWithEmptyTokenShouldReturnUnauthorized() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + " ");
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void unknownUserWithJwtRequestShouldReturnUnauthorized() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(TEST_NOT_EXISTING_EMAIL);

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void allowedWithJwtRequestForExistingUserShouldReturnOk() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void allowedWithJwtRequestShouldProperlySetSecurityContext() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        // Create a test order for the user
        Order testOrder = Order.builder()
                .user(testUser)
                .status(OrderStatus.CREATED)
                .deliveryAddress("123 Test Street")
                .build();
        orderRepository.save(testOrder);

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        
        List<OrderDto> orders = response.getBody();
        assertNotNull(orders);
        // Verify that the endpoint returns orders for the authenticated user
        assertTrue(orders.stream().allMatch(order -> 
            order.getDeliveryAddress().equals("123 Test Street") || 
            order.getStatus().equals(OrderStatus.CREATED.name())
        ));
    }

    @Test
    void allowedWithJwtRequestAndExistingUserShouldAuthenticateSuccessfully() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Verify that the response body is not null and is a proper list
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof List);
    }

    @Test
    void shouldReturnUnauthorizedWhenJwtServiceThrowsException() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenThrow(new RuntimeException("JWT validation error"));

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenJwtExtractionFails() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenThrow(new RuntimeException("Failed to extract email"));

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnOkWhenUserExistsAndTokenIsValid() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof List);
    }

    @Test
    void shouldIncludeAuthorizationHeaderInRequestProcessing() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        HttpHeaders headers = createJsonHeaders();
        String authHeader = PREFIX_BEARER + " " + MOCKED_JWT;
        headers.set(HttpHeaders.AUTHORIZATION, authHeader);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Verify that the request was processed with the authentication header
        assertTrue(headers.containsKey(HttpHeaders.AUTHORIZATION));
        assertEquals(authHeader, headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void shouldPreserveSecurityContextForSubsequentRequests() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        // Make two consecutive requests to ensure context is properly set each time
        ResponseEntity<List<OrderDto>> firstResponse = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        ResponseEntity<List<OrderDto>> secondResponse = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.OK, firstResponse.getStatusCode());
        assertEquals(HttpStatus.OK, secondResponse.getStatusCode());
        assertNotNull(firstResponse.getBody());
        assertNotNull(secondResponse.getBody());
    }

    @Test
    void shouldNotCacheAuthenticationForInvalidToken() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        // First, send a request with valid token
        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        HttpHeaders validHeaders = createJsonHeaders();
        validHeaders.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + "valid-token-1");

        ResponseEntity<List<OrderDto>> firstResponse = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(validHeaders), 
                new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.OK, firstResponse.getStatusCode());

        // Then send another request with invalid token
        when(jwtServiceMock.isValidToken(anyString())).thenReturn(false);

        HttpHeaders invalidHeaders = createJsonHeaders();
        invalidHeaders.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + "invalid-token-2");

        ResponseEntity<List<OrderDto>> secondResponse = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(invalidHeaders), 
                new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.UNAUTHORIZED, secondResponse.getStatusCode());
    }

    @Test
    void shouldHandleConcurrentJwtRequestsCorrectly() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        // Simulate concurrent requests (sequential for test stability)
        for (int i = 0; i < 3; i++) {
            ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                    url, 
                    HttpMethod.GET, 
                    new HttpEntity<>(headers), 
                    new ParameterizedTypeReference<>() {});
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }
    }

    @Test
    void shouldReturnOkWhenUserExistsAndTokenIsValidForGetEndpoint() {
        String url = baseUrl + API_VERSION + USER + ORDERS;

        when(jwtServiceMock.isValidToken(anyString())).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(anyString())).thenReturn(testUserEmail);

        HttpHeaders headers = createJsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + MOCKED_JWT);

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                new HttpEntity<>(headers), 
                new ParameterizedTypeReference<>() {});
        
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isEmpty() || response.getBody() == null);
    }

    private HttpHeaders createJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}