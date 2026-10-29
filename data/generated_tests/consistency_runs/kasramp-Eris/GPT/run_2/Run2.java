
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@TestPropertySource(locations = {"classpath:test.properties"},
        properties = {"spring.mvc.throw-exception-if-no-handler-found=true"})
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

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add(OPENWEATHERMAP_PROPERTY, () -> TEST_API_KEY);
        registry.add(SEGMENTIO_PROPERTY, () -> TEST_API_KEY);
    }

    @BeforeAll
    static void setup() {
        originalOpenWeatherMapApiKey = System.getProperty(OPENWEATHERMAP_API_KEY_PROPERTY);
        originalSegmentIoWriteApiKey = System.getProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY);
        setRequiredSystemProperties();
    }

    @BeforeEach
    void seedSystemProperties() {
        setRequiredSystemProperties();
    }

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(OPENWEATHERMAP_API_KEY_PROPERTY);
        System.clearProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY);
    }

    @AfterAll
    static void restoreSystemProperties() {
        restoreSystemProperty(OPENWEATHERMAP_API_KEY_PROPERTY, originalOpenWeatherMapApiKey);
        restoreSystemProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY, originalSegmentIoWriteApiKey);
    }

    @Test
    void shouldLoadApplicationContextSuccessfully() {
        assertNotNull(applicationContext);
    }

    @Test
    void shouldRegisterApplicationBeanInContext() {
        assertNotNull(applicationContext.getBean(Application.class));
    }

    @Test
    void shouldBindApiKeysFromTestConfiguration() {
        assertEquals(TEST_API_KEY, propertyUtils.getOpenWeatherMapApiKey());
        assertEquals(TEST_API_KEY, propertyUtils.getSegmentIoWriteApiKey());
    }

    @Test
    void shouldApplyTestPropertySourceOverride() {
        assertEquals("true", environment.getProperty("spring.mvc.throw-exception-if-no-handler-found"));
    }

    @Test
    void shouldSeedLegacySystemPropertiesForBootstrapCompatibility() {
        assertEquals(TEST_API_KEY, System.getProperty(OPENWEATHERMAP_API_KEY_PROPERTY));
        assertEquals(TEST_API_KEY, System.getProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY));
    }

    @Test
    void shouldBootstrapApplicationContextWithoutStartingWebServer() {
        assertDoesNotThrow(() -> {
            try (ConfigurableApplicationContext bootstrapContext = new SpringApplicationBuilder(Application.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            OPENWEATHERMAP_PROPERTY + "=" + TEST_API_KEY,
                            SEGMENTIO_PROPERTY + "=" + TEST_API_KEY)
                    .run()) {
                assertTrue(bootstrapContext.isActive());
            }
        });
    }

    private static void setRequiredSystemProperties() {
        System.setProperty(OPENWEATHERMAP_API_KEY_PROPERTY, TEST_API_KEY);
        System.setProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY, TEST_API_KEY);
    }

    private static void restoreSystemProperty(String propertyName, String propertyValue) {
        if (propertyValue == null) {
            System.clearProperty(propertyName);
            return;
        }

        System.setProperty(propertyName, propertyValue);
    }
}

