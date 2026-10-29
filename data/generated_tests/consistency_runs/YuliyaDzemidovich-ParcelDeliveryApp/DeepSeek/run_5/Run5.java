package com.github.yuliyadzemidovich.parceldeliveryapp.filter;

import com.github.yuliyadzemidovich.parceldeliveryapp.ParcelDeliveryAppApplication;
import com.github.yuliyadzemidovich.parceldeliveryapp.dto.OrderDto;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.Order;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.User;
import com.github.yuliyadzemidovich.parceldeliveryapp.repository.OrderRepository;
import com.github.yuliyadzemidovich.parceldeliveryapp.repository.UserRepository;
import com.github.yuliyadzemidovich.parceldeliveryapp.service.JwtService;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.API_VERSION;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.ORDERS;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PATH_ACTUATOR_HEALTH;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PREFIX_BEARER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ParcelDeliveryAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Profile("local")
@AutoConfigureTestDatabase
@Transactional
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
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
    private String validToken;
    private String existingEmail;
    private User existingUser;

    @BeforeEach
    void init() {
        baseUrl = "http://localhost:" + port + contextPath;
        validToken = "valid.jwt.token";
        existingEmail = "testuser@example.com";
        
        // Create and save a real user in the database
        existingUser = User.builder()
                .email(existingEmail)
                .password(passwordEncoder.encode("password123"))
                .role("USER")
                .build();
        userRepository.save(existingUser);

        // Mock JWT service behavior
        when(jwtServiceMock.isValidToken(validToken)).thenReturn(true);
        when(jwtServiceMock.extractUserEmail(validToken)).thenReturn(existingEmail);
    }

    @Test
    void shouldAllowAccessToActuatorHealthWithoutAuthentication() {
        String url = baseUrl + PATH_ACTUATOR_HEALTH;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void shouldReturnUnauthorizedWhenAccessingProtectedEndpointWithoutToken() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenAccessingProtectedEndpointWithInvalidToken() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        String invalidToken = "invalid.jwt.token";
        
        when(jwtServiceMock.isValidToken(invalidToken)).thenReturn(false);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + invalidToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenIsValidButUserDoesNotExist() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        String nonExistentEmail = "nonexistent@example.com";
        
        when(jwtServiceMock.extractUserEmail(validToken)).thenReturn(nonExistentEmail);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldAllowAccessToProtectedEndpointWithValidTokenAndExistingUser() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void shouldReturnUnauthorizedWhenAuthorizationHeaderHasWrongFormat() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, "WrongPrefix " + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenIsMalformed() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        String malformedToken = "malformed.token";
        
        when(jwtServiceMock.isValidToken(malformedToken)).thenReturn(false);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + malformedToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenIsExpired() {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        String expiredToken = "expired.jwt.token";
        
        when(jwtServiceMock.isValidToken(expiredToken)).thenReturn(false);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + expiredToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldAllowAccessToSwaggerEndpointsWithoutAuthentication() {
        String swaggerUrl = baseUrl + "/swagger-ui/index.html";
        ResponseEntity<String> response = restTemplate.getForEntity(swaggerUrl, String.class);
        
        // Swagger endpoints might redirect, but should not return 401
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void shouldAllowAccessToOpenApiEndpointsWithoutAuthentication() {
        String openApiUrl = baseUrl + "/v3/api-docs";
        ResponseEntity<String> response = restTemplate.getForEntity(openApiUrl, String.class);
        
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void shouldAllowAccessToLoginEndpointWithoutAuthentication() {
        String loginUrl = baseUrl + API_VERSION + "/login";
        ResponseEntity<String> response = restTemplate.getForEntity(loginUrl, String.class);
        
        // Login endpoint should not require authentication
        assertNotNull(response);
        // It might return 405 Method Not Allowed if GET is not supported, but should not be 401
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    }

    @Test
    void shouldAllowAccessToRegisterEndpointWithoutAuthentication() {
        String registerUrl = baseUrl + API_VERSION + "/user/register";
        ResponseEntity<String> response = restTemplate.getForEntity(registerUrl, String.class);
        
        // Register endpoint should not require authentication
        assertNotNull(response);
        // It might return 405 Method Not Allowed if GET is not supported, but should not be 401
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    }

    @Test
    void shouldAuthenticateAndProcessOrderCreationRequest() throws Exception {
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        OrderDto orderDto = new OrderDto();
        orderDto.setDeliveryAddress("123 Test Street");
        orderDto.setRecipientName("Test Recipient");
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        
        HttpEntity<OrderDto> requestEntity = new HttpEntity<>(orderDto, headers);
        
        ResponseEntity<OrderDto> response = restTemplate.exchange(
                url, HttpMethod.POST, requestEntity, OrderDto.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(orderDto.getDeliveryAddress(), response.getBody().getDeliveryAddress());
        assertEquals(orderDto.getRecipientName(), response.getBody().getRecipientName());
    }

    @Test
    void shouldAuthenticateAndRetrieveUserOrders() {
        // Create test orders
        Order order1 = Order.builder()
                .user(existingUser)
                .deliveryAddress("Address 1")
                .recipientName("Recipient 1")
                .status("PENDING")
                .build();
        
        Order order2 = Order.builder()
                .user(existingUser)
                .deliveryAddress("Address 2")
                .recipientName("Recipient 2")
                .status("PENDING")
                .build();
        
        orderRepository.save(order1);
        orderRepository.save(order2);
        
        String url = baseUrl + API_VERSION + USER + ORDERS;
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<>() {});
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void shouldAuthenticateAndRetrieveOrderById() {
        Order order = Order.builder()
                .user(existingUser)
                .deliveryAddress("Test Address")
                .recipientName("Test Recipient")
                .status("PENDING")
                .build();
        Order savedOrder = orderRepository.save(order);
        
        String url = baseUrl + API_VERSION + USER + ORDERS + "/" + savedOrder.getId();
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        HttpEntity<Object> requestEntity = new HttpEntity<>(null, headers);
        
        ResponseEntity<OrderDto> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, OrderDto.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(savedOrder.getDeliveryAddress(), response.getBody().getDeliveryAddress());
    }

    @Test
    void shouldAuthenticateAndUpdateDeliveryAddress() {
        Order order = Order.builder()
                .user(existingUser)
                .deliveryAddress("Old Address")
                .recipientName("Test Recipient")
                .status("PENDING")
                .build();
        Order savedOrder = orderRepository.save(order);
        
        String newAddress = "New Updated Address";
        String url = baseUrl + API_VERSION + USER + ORDERS + "/" + savedOrder.getId() + "/address";
        
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE);
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + validToken);
        
        HttpEntity<String> requestEntity = new HttpEntity<>(newAddress, headers);
        
        ResponseEntity<OrderDto> response = restTemplate.exchange(
                url, HttpMethod.PUT, requestEntity, OrderDto.class);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(newAddress, response.getBody().getDeliveryAddress());
    }
}