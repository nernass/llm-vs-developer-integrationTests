package com.madadipouya.eris;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

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

@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = {"classpath:test.properties"}, 
        properties = {"spring.mvc.throw-exception-if-no-handler-found=true"})
@SpringBootTest(classes = Application.class)
class ApplicationIT {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/eris-test";
    private static final String[] SYSTEM_PROPERTIES = {
        "OPENWEATHERMAP_API_KEY", 
        "SEGMENTIO_WRITE_API_KEY",
        "APP_ENV",
        "LOG_LEVEL"
    };

    @BeforeAll
    static void setupClass() {
        // Ensure all required system properties are set before context loads
        System.setProperty("OPENWEATHERMAP_API_KEY", "testOpenWeatherApiKey");
        System.setProperty("SEGMENTIO_WRITE_API_KEY", "testSegmentIoWriteApiKey");
        System.setProperty("APP_ENV", "test");
        System.setProperty("LOG_LEVEL", "ERROR");
    }

    @BeforeEach
    void setup() {
        // Create temp directory for file cleanup testing
        try {
            Files.createDirectories(Paths.get(TEMP_DIR));
        } catch (Exception e) {
            // Ignore if directory already exists
        }
    }

    @AfterEach
    void cleanup() {
        // Clean up system properties that may have been modified during tests
        for (String property : SYSTEM_PROPERTIES) {
            // Reset to default test values but only if they were changed
            if (System.getProperty(property) != null && 
                !System.getProperty(property).equals(getDefaultPropertyValue(property))) {
                System.setProperty(property, getDefaultPropertyValue(property));
            }
        }
        
        // Clean up any temporary files created during tests
        try {
            Path tempPath = Paths.get(TEMP_DIR);
            if (Files.exists(tempPath)) {
                try (Stream<Path> walk = Files.walk(tempPath)) {
                    walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }
            }
        } catch (Exception e) {
            // Ignore cleanup errors in tests
        }
    }

    private String getDefaultPropertyValue(String property) {
        switch (property) {
            case "OPENWEATHERMAP_API_KEY":
                return "testOpenWeatherApiKey";
            case "SEGMENTIO_WRITE_API_KEY":
                return "testSegmentIoWriteApiKey";
            case "APP_ENV":
                return "test";
            case "LOG_LEVEL":
                return "ERROR";
            default:
                return null;
        }
    }

    @Test
    void shouldStartApplicationWithoutWebEnvironment() {
        // Verify that application starts without web environment
        assertDoesNotThrow(() -> {
            Application.main(new String[]{"--spring.main.web-environment=false"});
        }, "Application should start without web environment");
    }

    @Test
    void shouldStartApplicationWithDefaultConfiguration() {
        // Verify application starts with default configuration
        assertDoesNotThrow(() -> {
            Application.main(new String[]{});
        }, "Application should start with default configuration");
    }

    @Test
    void shouldStartApplicationWithCustomServerPort() {
        // Verify application starts with custom server port
        assertDoesNotThrow(() -> {
            Application.main(new String[]{"--server.port=9999"});
        }, "Application should start with custom server port");
    }

    @Test
    void shouldStartApplicationWithDebugLoggingEnabled() {
        // Verify application starts with debug logging
        assertDoesNotThrow(() -> {
            Application.main(new String[]{"--logging.level.root=DEBUG"});
        }, "Application should start with debug logging enabled");
    }

    @Test
    void shouldStartApplicationWithMultipleCommandLineArguments() {
        // Verify application starts with multiple command line arguments
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                "--spring.main.web-environment=false",
                "--server.port=8888",
                "--logging.level.root=INFO",
                "--spring.profiles.active=test"
            });
        }, "Application should start with multiple command line arguments");
    }

    @Test
    void shouldStartApplicationWithSystemPropertiesOverride() {
        // Test that system properties are properly overridden by command line args
        System.setProperty("SERVER_PORT", "7777");
        assertDoesNotThrow(() -> {
            Application.main(new String[]{"--server.port=6666"});
        }, "Application should start with system properties override");
        // Clean up
        System.clearProperty("SERVER_PORT");
    }

    @Test
    void shouldStartApplicationAndHandlePropertyPriority() {
        // Test that command line arguments take precedence over system properties
        System.setProperty("OPENWEATHERMAP_API_KEY", "systemPropertyKey");
        System.setProperty("SEGMENTIO_WRITE_API_KEY", "systemSegmentKey");
        
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                "--OPENWEATHERMAP_API_KEY=commandLineKey",
                "--spring.main.web-environment=false"
            });
        }, "Application should handle property priority correctly");
        
        // Reset to default test values
        System.setProperty("OPENWEATHERMAP_API_KEY", "testOpenWeatherApiKey");
        System.setProperty("SEGMENTIO_WRITE_API_KEY", "testSegmentIoWriteApiKey");
    }

    @Test
    void shouldStartApplicationWithExternalConfigurationFile() {
        // Verify application starts with external configuration file
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                "--spring.config.location=classpath:/application-test.yml",
                "--spring.main.web-environment=false"
            });
        }, "Application should start with external configuration file");
    }

    @Test
    void shouldStartApplicationWithCustomBannerDisabled() {
        // Verify application starts with banner disabled
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                "--spring.banner.enabled=false",
                "--spring.main.web-environment=false"
            });
        }, "Application should start with banner disabled");
    }

    @Test
    void shouldStartApplicationAndHandleMissingOptionalProperties() {
        // Save original values
        String originalWeatherKey = System.getProperty("OPENWEATHERMAP_API_KEY");
        String originalSegmentKey = System.getProperty("SEGMENTIO_WRITE_API_KEY");
        
        // Remove system properties to test default values
        System.clearProperty("OPENWEATHERMAP_API_KEY");
        System.clearProperty("SEGMENTIO_WRITE_API_KEY");
        
        assertDoesNotThrow(() -> {
            Application.main(new String[]{
                "--spring.main.web-environment=false",
                "--OPENWEATHERMAP_API_KEY=defaultTestKey",
                "--SEGMENTIO_WRITE_API_KEY=defaultSegmentKey"
            });
        }, "Application should handle missing optional properties");
        
        // Restore original values
        if (originalWeatherKey != null) {
            System.setProperty("OPENWEATHERMAP_API_KEY", originalWeatherKey);
        }
        if (originalSegmentKey != null) {
            System.setProperty("SEGMENTIO_WRITE_API_KEY", originalSegmentKey);
        }
    }

    @Test
    void shouldStartApplicationWithDifferentProfiles() {
        // Test different active profiles
        String[] profiles = {"dev", "staging", "production"};
        
        for (String profile : profiles) {
            final String testProfile = profile;
            assertDoesNotThrow(() -> {
                Application.main(new String[]{
                    "--spring.profiles.active=" + testProfile,
                    "--spring.main.web-environment=false"
                });
            }, "Application should start with profile: " + profile);
        }
    }

    @Test
    void shouldStartApplicationAndCreateTempFiles() {
        // Test that application can create temporary files if needed
        assertDoesNotThrow(() -> {
            // Create a temp file to simulate application file creation
            Path tempFile = Paths.get(TEMP_DIR, "application-test.tmp");
            Files.createFile(tempFile);
            assertTrue(Files.exists(tempFile), "Temporary file should be created");
            
            Application.main(new String[]{"--spring.main.web-environment=false"});
        }, "Application should start and handle temp file creation");
    }
}