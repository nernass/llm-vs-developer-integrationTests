package com.github.yuliyadzemidovich.parceldeliveryapp.filter;

import com.github.yuliyadzemidovich.parceldeliveryapp.ParcelDeliveryAppApplication;
import com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil;
import com.github.yuliyadzemidovich.parceldeliveryapp.dto.OrderDto;
import com.github.yuliyadzemidovich.parceldeliveryapp.entity.User;
import com.github.yuliyadzemidovich.parceldeliveryapp.repository.OrderRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.ADMIN;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.API_VERSION;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.ORDERS;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PATH_ACTUATOR_HEALTH;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.PREFIX_BEARER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.Constants.USER;
import static com.github.yuliyadzemidovich.parceldeliveryapp.TestUtil.TEST_NOT_EXISTING_EMAIL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = ParcelDeliveryAppApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@AutoConfigureTestDatabase
@Transactional
class JWTAuthorizationFilterIntegrationTest {

    private static final String INVALID_JWT = "invalid.jwt.token";

    @LocalServerPort
    private int port;

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String baseUrl;
    private User existingUser;

    @BeforeEach
    void init() {
        baseUrl = "http://localhost:" + port + contextPath;
        runInNewTransaction(() -> {
            orderRepo.deleteAll();
            userRepo.deleteAll();
        });
        existingUser = runInNewTransaction(() -> userRepo.save(TestUtil.getUser1()));
    }

    @AfterEach
    void cleanUp() {
        runInNewTransaction(() -> {
            orderRepo.deleteAll();
            userRepo.deleteAll();
        });
    }

    @Test
    void shouldAllowActuatorHealthWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + PATH_ACTUATOR_HEALTH, String.class);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void shouldReturn401WhenAuthorizationHeaderIsMissing() {
        ResponseEntity<String> response = restTemplate.exchange(
                userOrdersUrl(),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                String.class);

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturn401WhenJwtIsMalformed() {
        ResponseEntity<String> response = restTemplate.exchange(
                userOrdersUrl(),
                HttpMethod.GET,
                new HttpEntity<>(authorizedJsonHeaders(INVALID_JWT)),
                String.class);

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturn401WhenJwtIsValidButUserDoesNotExist() {
        String validTokenForUnknownUser = jwtService.generateToken(TEST_NOT_EXISTING_EMAIL);

        ResponseEntity<String> response = restTemplate.exchange(
                userOrdersUrl(),
                HttpMethod.GET,
                new HttpEntity<>(authorizedJsonHeaders(validTokenForUnknownUser)),
                String.class);

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void shouldReturn200WhenJwtIsValidAndUserExists() {
        String validToken = jwtService.generateToken(existingUser.getEmail());

        ResponseEntity<List<OrderDto>> response = restTemplate.exchange(
                userOrdersUrl(),
                HttpMethod.GET,
                new HttpEntity<>(authorizedJsonHeaders(validToken)),
                new ParameterizedTypeReference<>() { });

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void shouldReturn403WhenUserJwtTargetsAdminOrdersEndpoint() {
        String validToken = jwtService.generateToken(existingUser.getEmail());

        ResponseEntity<String> response = restTemplate.exchange(
                adminOrdersUrl(),
                HttpMethod.GET,
                new HttpEntity<>(authorizedJsonHeaders(validToken)),
                String.class);

        assertNotNull(response);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    private String userOrdersUrl() {
        return baseUrl + API_VERSION + USER + ORDERS;
    }

    private String adminOrdersUrl() {
        return baseUrl + API_VERSION + ADMIN + ORDERS;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    private HttpHeaders authorizedJsonHeaders(String jwt) {
        HttpHeaders headers = jsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, PREFIX_BEARER + jwt);
        return headers;
    }

    private void runInNewTransaction(Runnable action) {
        newTransactionTemplate().executeWithoutResult(status -> action.run());
    }

    private <T> T runInNewTransaction(Supplier<T> supplier) {
        return newTransactionTemplate().execute(status -> supplier.get());
    }

    private TransactionTemplate newTransactionTemplate() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
    }
}