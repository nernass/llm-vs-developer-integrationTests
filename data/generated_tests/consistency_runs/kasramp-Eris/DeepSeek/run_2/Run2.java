package com.madadipouya.eris;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/*
 * This file is part of Eris Weather API.
 *
 * Eris Weather API is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3
 * as published by the Free Software Foundation.
 *
 * Eris Weather API is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.  <http://www.gnu.org/licenses/>
 *
 * Author(s):
 *
 * © 2017-2023 Kasra Madadipouya <kasra@madadipouya.com>
 */

@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = {"classpath:test.properties"},
        properties = {
                "spring.mvc.throw-exception-if-no-handler-found=true",
                "spring.main.web-environment=false"
        })
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    private static final Properties originalSystemProperties = new Properties();

    @BeforeAll
    static void setup() {
        // Save original system properties
        originalSystemProperties.putAll(System.getProperties());
        
        // Set required system properties for application bootstrap
        System.setProperty("OPENWEATHERMAP_API_KEY", "test-api-key-12345");
        System.setProperty("SEGMENTIO_WRITE_API_KEY", "test-segment-key-67890");
    }

    @AfterEach
    void cleanup() {
        // Reset system properties to their original state after each test
        // This prevents test pollution between test methods
        System.setProperties(originalSystemProperties);
    }

    @Test
    void testApplicationBootstrap_shouldStartWithoutExceptions() {
        assertDoesNotThrow(() -> {
            Application.main(new String[]{"--spring.main.web-environment=false"});
        });
    }

    @Test
    void testApplicationContext_shouldLoadSuccessfully() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.containsBean("application")).isTrue();
    }

    @Test
    void testApplicationContext_shouldHaveRequiredProperties() {
        String openWeatherApiKey = System.getProperty("OPENWEATHERMAP_API_KEY");
        String segmentIoWriteKey = System.getProperty("SEGMENTIO_WRITE_API_KEY");
        
        assertThat(openWeatherApiKey).isEqualTo("test-api-key-12345");
        assertThat(segmentIoWriteKey).isEqualTo("test-segment-key-67890");
    }

    @Test
    void testApplicationMain_shouldHandleMultipleArguments() {
        String[] args = {
                "--spring.main.web-environment=false",
                "--server.port=8080",
                "--spring.profiles.active=test"
        };
        
        assertDoesNotThrow(() -> Application.main(args));
    }

    @Test
    void testApplicationMain_shouldHandleEmptyArguments() {
        assertDoesNotThrow(() -> Application.main(new String[]{}));
    }

    @Test
    void testApplicationMain_shouldHandleNullArguments() {
        assertDoesNotThrow(() -> Application.main(null));
    }

    @Test
    void testApplicationContext_shouldHaveSpringBootConfiguration() {
        SpringBootTest springBootTestAnnotation = Application.class.getAnnotation(SpringBootTest.class);
        assertThat(springBootTestAnnotation).isNotNull();
        
        SpringBootApplication springBootAppAnnotation = Application.class.getAnnotation(SpringBootApplication.class);
        assertThat(springBootAppAnnotation).isNotNull();
    }

    @Test
    void testApplicationContext_shouldScanCorrectPackages() {
        SpringBootApplication springBootAppAnnotation = Application.class.getAnnotation(SpringBootApplication.class);
        String[] scanBasePackages = springBootAppAnnotation.scanBasePackages();
        
        assertThat(scanBasePackages).contains("com.madadipouya.eris");
    }

    @Test
    void testApplicationMain_shouldHaveCorrectSignature() throws NoSuchMethodException {
        java.lang.reflect.Method mainMethod = Application.class.getMethod("main", String[].class);
        assertThat(mainMethod).isNotNull();
        assertThat(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers())).isTrue();
        assertThat(mainMethod.getReturnType()).isEqualTo(void.class);
    }

    @Test
    void testApplicationBootstrap_withWebEnvironmentDisabled_shouldStartSuccessfully() {
        assertDoesNotThrow(() -> {
            Application.main(new String[]{"--spring.main.web-environment=false"});
        });
    }

    @Test
    void testApplicationBootstrap_withCustomProperties_shouldStartSuccessfully() {
        String[] args = {
                "--spring.main.web-environment=false",
                "--openweathermap.api.key=custom-test-key",
                "--segmentio.write.api.key=custom-segment-key"
        };
        
        assertDoesNotThrow(() -> Application.main(args));
    }
}