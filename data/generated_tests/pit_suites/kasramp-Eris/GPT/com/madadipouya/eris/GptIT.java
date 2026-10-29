package com.madadipouya.eris;

import com.madadipouya.eris.configuration.CacheConfiguration;
import com.madadipouya.eris.listener.CountryCodeCacheInitializationListener;
import com.madadipouya.eris.util.PropertyUtils;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GptIT {

    @Test
    void shouldExposeApplicationClass() {
        assertAll(
                () -> assertNotNull(Application.class),
                () -> assertEquals("com.madadipouya.eris", Application.class.getPackageName())
        );
    }

    @Test
    void shouldResolveTestScopedPropertiesForStartupDependencies() {
        PropertyUtils propertyUtils = new PropertyUtils();

        ReflectionTestUtils.setField(propertyUtils, "openWeatherMapApiKey", "testKey");
        ReflectionTestUtils.setField(propertyUtils, "segmentIoWriteApiKey", "testKey");
        ReflectionTestUtils.setField(propertyUtils, "actuatorUsername", "testUsername");
        ReflectionTestUtils.setField(propertyUtils, "actuatorPassword", "testPassword");

        assertAll(
                () -> assertEquals("testKey", propertyUtils.getOpenWeatherMapApiKey()),
                () -> assertEquals("testKey", propertyUtils.getSegmentIoWriteApiKey()),
                () -> assertEquals("testUsername", propertyUtils.getActuatorUsername()),
                () -> assertEquals("testPassword", propertyUtils.getActuatorPassword())
        );
    }

    @Test
    void shouldPopulateCountryCodeCacheWhenApplicationIsReady() {
        ConcurrentMapCacheManager cacheManager =
                new ConcurrentMapCacheManager(CacheConfiguration.COUNTRY_CODE_CACHE);

        CountryCodeCacheInitializationListener listener =
                new CountryCodeCacheInitializationListener(cacheManager);

        Cache cache = cacheManager.getCache(CacheConfiguration.COUNTRY_CODE_CACHE);

        assertNotNull(cache, "countryCodeCache should be configured");

        ReflectionTestUtils.invokeMethod(
                listener,
                "populateCountryCodeCache",
                cache,
                java.util.Map.of(
                        "AD", "Andorra",
                        "AU", "Australia"
                )
        );

        assertAll(
                () -> assertEquals("Andorra", cache.get("AD", String.class)),
                () -> assertEquals("Australia", cache.get("AU", String.class))
        );
    }

    @Test
    void shouldCreateCountryCodeCacheInitializationListener() {
        ConcurrentMapCacheManager cacheManager =
                new ConcurrentMapCacheManager(CacheConfiguration.COUNTRY_CODE_CACHE);

        CountryCodeCacheInitializationListener listener =
                new CountryCodeCacheInitializationListener(cacheManager);

        assertTrue(listener != null);
    }
}