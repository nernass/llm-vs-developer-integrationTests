
package com.madadipouya.eris;

import com.madadipouya.eris.util.PropertyUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
@TestPropertySource(
        locations = {"classpath:test.properties"},
        properties = {
                "spring.mvc.throw-exception-if-no-handler-found=true",
                "openweathermap.api.key=testKey",
                "segmentio.write.api.key=testKey"
        }
)
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationIT {

    private static final String OPENWEATHERMAP_API_KEY_PROPERTY = "OPENWEATHERMAP_API_KEY";
    private static final String SEGMENTIO_WRITE_API_KEY_PROPERTY = "SEGMENTIO_WRITE_API_KEY";
    private static final String OPENWEATHERMAP_PROPERTY = "openweathermap.api.key";
    private static final String SEGMENTIO_PROPERTY = "segmentio.write.api.key";
    private static final String TEST_API_KEY = "testKey";

    private static String originalOpenWeatherMapApiKey;
    private static String originalSegmentIoWriteApiKey;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private PropertyUtils propertyUtils;

    @BeforeAll
    static void setup() {
        originalOpenWeatherMapApiKey = System.getProperty(OPENWEATHERMAP_API_KEY_PROPERTY);
        originalSegmentIoWriteApiKey = System.getProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY);
        seedRequiredSystemProperties();
    }

    @BeforeEach
    void beforeEach() {
        seedRequiredSystemProperties();
    }

    @AfterEach
    void afterEach() {
        System.clearProperty(OPENWEATHERMAP_API_KEY_PROPERTY);
        System.clearProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY);
    }

    @AfterAll
    static void tearDown() {
        restoreSystemProperty(OPENWEATHERMAP_API_KEY_PROPERTY, originalOpenWeatherMapApiKey);
        restoreSystemProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY, originalSegmentIoWriteApiKey);
    }

    @Test
    void shouldLoadApplicationContextSuccessfully() {
        assertNotNull(applicationContext);
    }

    @Test
    void shouldRegisterApplicationConfigurationBean() {
        assertTrue(applicationContext.getBeanNamesForType(Application.class).length > 0);
    }

    @Test
    void shouldBindApiKeysFromTestProperties() {
        assertEquals(TEST_API_KEY, propertyUtils.getOpenWeatherMapApiKey());
        assertEquals(TEST_API_KEY, propertyUtils.getSegmentIoWriteApiKey());
    }

    @Test
    void shouldOverrideActuatorCredentialsFromTestProperties() {
        assertEquals("testUsername", propertyUtils.getActuatorUsername());
        assertEquals("testPassword", propertyUtils.getActuatorPassword());
    }

    @Test
    void shouldExposeMvcExceptionHandlingFlagFromTestPropertySource() {
        assertEquals("true", environment.getProperty("spring.mvc.throw-exception-if-no-handler-found"));
    }

    @Test
    void shouldSeedLegacySystemPropertiesForBootstrapCompatibility() {
        assertEquals(TEST_API_KEY, System.getProperty(OPENWEATHERMAP_API_KEY_PROPERTY));
        assertEquals(TEST_API_KEY, System.getProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY));
    }

    @Test
    void shouldBootstrapStandaloneApplicationContextWithoutStartingWebEnvironment() {
        assertDoesNotThrow(() -> {
            try (ConfigurableApplicationContext bootstrapContext = new SpringApplicationBuilder(Application.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            OPENWEATHERMAP_PROPERTY + "=" + TEST_API_KEY,
                            SEGMENTIO_PROPERTY + "=" + TEST_API_KEY,
                            "actuator.username=testUsername",
                            "actuator.password=testPassword")
                    .run()) {
                assertTrue(bootstrapContext.isActive());
                assertNotNull(bootstrapContext.getBean(PropertyUtils.class));
            }
        });
    }

    private static void seedRequiredSystemProperties() {
        System.setProperty(OPENWEATHERMAP_API_KEY_PROPERTY, TEST_API_KEY);
        System.setProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY, TEST_API_KEY);
    }

    private static void restoreSystemProperty(String propertyName, String originalValue) {
        if (originalValue == null) {
            System.clearProperty(propertyName);
            return;
        }

        System.setProperty(propertyName, originalValue);
    }
}
