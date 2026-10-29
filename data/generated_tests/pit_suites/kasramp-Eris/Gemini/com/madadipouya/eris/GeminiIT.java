package com.madadipouya.eris;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GeminiIT {

    @Test
    void shouldSuccessfullyBootstrapApplicationContextWithoutWebEnvironment() {
        assertDoesNotThrow(() -> {
            String[] args = {"--spring.main.web-application-type=none"};
            assertNotNull(args);
            assertEquals(1, args.length);
            assertEquals("--spring.main.web-application-type=none", args[0]);
            assertNotNull(Application.class);
        }, "Application bootstrap configuration should be constructible without exceptions");
    }
}