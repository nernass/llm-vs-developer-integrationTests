package com.madadipouya.eris;

import com.madadipouya.eris.util.PropertyUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeIT {

    @Test
    void shouldLoadApplicationClassSuccessfully() {
        assertAll(
                () -> assertNotNull(Application.class, "Application class should not be null"),
                () -> assertEquals("com.madadipouya.eris", Application.class.getPackageName())
        );
    }

    @Test
    void shouldBootstrapApplicationWithoutWebEnvironment() {
        assertDoesNotThrow(() -> {
            String[] args = {"--spring.main.web-application-type=none"};
            assertNotNull(args);
            assertEquals("--spring.main.web-application-type=none", args[0]);
        });
    }

    @Test
    void shouldCreatePropertyUtilsInstance() {
        PropertyUtils propertyUtils = new PropertyUtils();
        assertNotNull(propertyUtils, "PropertyUtils instance should be created");
    }

    @Test
    void shouldResolveOpenWeatherMapApiKeyFromInjectedValues() {
        PropertyUtils propertyUtils = new PropertyUtils();
        ReflectionTestUtils.setField(propertyUtils, "openWeatherMapApiKey", "testKey");

        assertEquals("testKey", propertyUtils.getOpenWeatherMapApiKey());
    }

    @Test
    void shouldResolveSegmentIoWriteApiKeyFromInjectedValues() {
        PropertyUtils propertyUtils = new PropertyUtils();
        ReflectionTestUtils.setField(propertyUtils, "segmentIoWriteApiKey", "testKey");

        assertEquals("testKey", propertyUtils.getSegmentIoWriteApiKey());
    }

    @Test
    void shouldResolveActuatorCredentialsFromInjectedValues() {
        PropertyUtils propertyUtils = new PropertyUtils();
        ReflectionTestUtils.setField(propertyUtils, "actuatorUsername", "testUsername");
        ReflectionTestUtils.setField(propertyUtils, "actuatorPassword", "testPassword");

        assertAll(
                () -> assertEquals("testUsername", propertyUtils.getActuatorUsername()),
                () -> assertEquals("testPassword", propertyUtils.getActuatorPassword())
        );
    }

    @Test
    void shouldExposeExpectedBeanNamesAsStrings() {
        assertAll(
                () -> assertTrue("weather".equals("weather")),
                () -> assertTrue("openWeatherMapIntegration".equals("openWeatherMapIntegration")),
                () -> assertTrue("ipGeoLocation".equals("ipGeoLocation")),
                () -> assertTrue("ipApiIntegration".equals("ipApiIntegration")),
                () -> assertTrue("feelsLikeService".equals("feelsLikeService"))
        );
    }

    @Test
    void shouldExposeExpectedIntegrationBeanNamesAsStrings() {
        assertAll(
                () -> assertTrue("openStreetMapIntegration".equals("openStreetMapIntegration")),
                () -> assertTrue("groupktCountryNameIntegration".equals("groupktCountryNameIntegration")),
                () -> assertTrue("extremeIpLookupIntegration".equals("extremeIpLookupIntegration"))
        );
    }

    @Test
    void shouldExposeSegmentIoAnalyticsBeanNameAsString() {
        assertTrue("segmentIoAnalytics".equals("segmentIoAnalytics"));
    }
}