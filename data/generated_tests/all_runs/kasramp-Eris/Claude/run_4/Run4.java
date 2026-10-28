
package com.madadipouya.eris;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
* © 2017-2022 Kasra Madadipouya <kasra@madadipouya.com>
*/

@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = {"classpath:test.properties"}
        , properties = {"spring.mvc.throw-exception-if-no-handler-found=true"})
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationIT {

    private static final String OPENWEATHERMAP_API_KEY_PROPERTY = "OPENWEATHERMAP_API_KEY";
    private static final String SEGMENTIO_WRITE_API_KEY_PROPERTY = "SEGMENTIO_WRITE_API_KEY";

    @BeforeAll
    static void setup() {
        System.setProperty(OPENWEATHERMAP_API_KEY_PROPERTY, "testKey");
        System.setProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY, "testKey");
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty(OPENWEATHERMAP_API_KEY_PROPERTY);
        System.clearProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY);
    }

    @Test
    void testApplicationBootstrap() {
        Application.main(new String[]{"--spring.main.web-environment=false"});
    }

    @Test
    void shouldLoadApplicationContextSuccessfully(ApplicationContext applicationContext) {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void shouldNotThrowExceptionWhenMainRunMultipleTimes() {
        assertThatCode(() -> Application.main(new String[]{"--spring.main.web-environment=false"}))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRegisterApplicationBeanInContext(ApplicationContext applicationContext) {
        assertThat(applicationContext.getBean(Application.class)).isNotNull();
    }

    @Test
    void shouldStartWithAdditionalDisabledWebEnvironmentArguments() {
        assertThatCode(() -> Application.main(new String[]{
                "--spring.main.web-environment=false",
                "--spring.main.banner-mode=off"
        })).doesNotThrowAnyException();
    }

    @Test
    void shouldHaveRequiredSystemPropertiesSetBeforeContextStarts() {
        assertThat(System.getProperty(OPENWEATHERMAP_API_KEY_PROPERTY)).isEqualTo("testKey");
        assertThat(System.getProperty(SEGMENTIO_WRITE_API_KEY_PROPERTY)).isEqualTo("testKey");
    }
}
