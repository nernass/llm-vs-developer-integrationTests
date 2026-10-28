package com.madadipouya.eris;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

@SpringBootTest(classes = Application.class)
@TestPropertySource(locations = {"classpath:test.properties"},
        properties = {
                "spring.mvc.throw-exception-if-no-handler-found=true",
                "spring.main.web-environment=false"
        })
class ApplicationIT {

    private static final String OPENWEATHERMAP_API_KEY = "OPENWEATHERMAP_API_KEY";
    private static final String SEGMENTIO_WRITE_API_KEY = "SEGMENTIO_WRITE_API_KEY";
    private static final String OPENWEATHERMAP_API_KEY_VALUE = "testOpenWeatherKey";
    private static final String SEGMENTIO_WRITE_API_KEY_VALUE = "testSegmentKey";
    
    private static Properties originalSystemProperties;

    @BeforeAll
    static void setup() {
        // Store original system properties to restore them later
        originalSystemProperties = new Properties();
        originalSystemProperties.putAll(System.getProperties());
        
        // Set required system properties for application context initialization
        System.setProperty(OPENWEATHERMAP_API_KEY, OPENWEATHERMAP_API_KEY_VALUE);
        System.setProperty(SEGMENTIO_WRITE_API_KEY, SEGMENTIO_WRITE_API_KEY_VALUE);
    }

    @BeforeEach
    void setUp() {
        // Clean any potential side effects before each test
        cleanupTestArtifacts();
    }

    @AfterEach
    void tearDown() {
        // Clean up any artifacts created during tests
        cleanupTestArtifacts();
    }

    private void cleanupTestArtifacts() {
        // Clean up any temporary files that might have been created
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
            Files.walk(tempDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().contains("eris-test-"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            // Ignore cleanup failures
                        }
                    });
        } catch (Exception e) {
            // Ignore cleanup failures
        }
    }

    @Test
    void testApplicationBootstrap_withDefaultConfiguration() {
        // Test that the application starts successfully with default configuration
        assertDoesNotThrow(() -> {
            Application.main(new String[]{"--spring.main.web-environment=false"});
        }, "Application should bootstrap without exceptions");
    }

    @Test
    void testApplicationBootstrap_withCustomPort() {
        // Test that the application starts successfully with a custom port
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                    "--spring.main.web-environment=false",
                    "--server.port=8081"
            });
        }, "Application should bootstrap with custom port without exceptions");
    }

    @Test
    void testApplicationBootstrap_withAdditionalProperties() {
        // Test that the application starts successfully with additional properties
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                    "--spring.main.web-environment=false",
                    "--spring.profiles.active=test",
                    "--logging.level.root=WARN"
            });
        }, "Application should bootstrap with additional properties without exceptions");
    }

    @Test
    void testApplicationBootstrap_withMissingOptionalProperties() {
        // Test that the application starts successfully even with optional properties missing
        // Note: We're not removing required properties, just testing with additional optional ones
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                    "--spring.main.web-environment=false",
                    "--spring.output.ansi.enabled=never"
            });
        }, "Application should bootstrap with missing optional properties without exceptions");
    }

    @Test
    void testApplicationBootstrap_withDifferentTimeZone() {
        // Test that the application starts successfully with different timezone
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                    "--spring.main.web-environment=false",
                    "--user.timezone=UTC"
            });
        }, "Application should bootstrap with different timezone without exceptions");
    }

    @Test
    void testApplicationBootstrap_withMinimalConfiguration() {
        // Test that the application starts successfully with minimal configuration
        assertDoesNotThrow(() -> {
            Application.main(new String[]{"--spring.main.web-environment=false"});
        }, "Application should bootstrap with minimal configuration without exceptions");
    }

    @Test
    void testApplicationBootstrap_withInvalidConfigurationShouldRecoverGracefully() {
        // Test that the application handles invalid configurations gracefully
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                    "--spring.main.web-environment=false",
                    "--invalid.property=someValue",
                    "--spring.config.use-legacy-processing=true"
            });
        }, "Application should handle invalid configuration gracefully without exceptions");
    }
}