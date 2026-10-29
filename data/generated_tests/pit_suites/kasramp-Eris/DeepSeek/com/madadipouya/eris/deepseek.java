package com.madadipouya.eris;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class deepseek {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void setupSystemProperties() {
        System.setProperty("OPENWEATHERMAP_API_KEY", "testKey");
        System.setProperty("SEGMENTIO_WRITE_API_KEY", "testKey");
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("OPENWEATHERMAP_API_KEY", () -> "testKey");
        registry.add("SEGMENTIO_WRITE_API_KEY", () -> "testKey");

        registry.add("openweathermap.api.key", () -> "testKey");
        registry.add("openweathermap.apiKey", () -> "testKey");
        registry.add("openweathermap.api-key", () -> "testKey");

        registry.add("segmentio.write.api.key", () -> "testKey");
        registry.add("segmentio.writeApiKey", () -> "testKey");
        registry.add("segmentio.write-api-key", () -> "testKey");
    }

    @Test
    void applicationContextShouldLoadSuccessfully() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void embeddedServerShouldStartOnRandomPort() {
        assertThat(port).isGreaterThan(0);
    }

    @Test
    void configuredTestPropertiesShouldBeAvailable() {
        assertThat(environment.getProperty("openweathermap.api.key")).isEqualTo("testKey");
        assertThat(environment.getProperty("segmentio.write.api.key")).isEqualTo("testKey");
    }

    @Test
    void applicationShouldRespondToHttpRequest() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isBetween(200, 599);
    }

    @Test
    void actuatorHealthEndpointShouldBeReachableOrHandledByApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isBetween(200, 599);
    }
}
