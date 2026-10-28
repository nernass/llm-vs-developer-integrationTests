/********************************************************************************
 * Copyright (c) 2022, 2023 T-Systems International GmbH
 * Copyright (c) 2022, 2023 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.tractusx.autosetup.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.eclipse.tractusx.autosetup.entity.AppDetails;
import org.eclipse.tractusx.autosetup.entity.AppServiceCatalog;
import org.eclipse.tractusx.autosetup.entity.AppServiceCatalogAndCustomerMapping;
import org.eclipse.tractusx.autosetup.model.AppDetailsRequest;
import org.eclipse.tractusx.autosetup.model.AppServiceCatalogAndCustomerMappingPojo;
import org.eclipse.tractusx.autosetup.model.AppServiceCatalogPojo;
import org.eclipse.tractusx.autosetup.repository.AppRepository;
import org.eclipse.tractusx.autosetup.repository.AppServiceCatalogAndCustomerMappingRepository;
import org.eclipse.tractusx.autosetup.repository.AppServiceCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
public class AppDetailsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private AppServiceCatalogRepository catalogRepository;

    @Autowired
    private AppServiceCatalogAndCustomerMappingRepository mappingRepository;

    private AppDetails testAppDetails;
    private AppServiceCatalog testCatalog;
    private AppServiceCatalogAndCustomerMapping testMapping;

    @BeforeEach
    void setUp() {
        // Clean up any leftover data (though @Transactional rollback per method handles this)
        // However, to avoid conflicts between tests, we can clean explicitly if needed.
        // But since we use @Transactional, each test runs in its own transaction and rolls back,
        // so we don't need to clean manually. We can also insert seed data here.
        appRepository.deleteAll();
        catalogRepository.deleteAll();
        mappingRepository.deleteAll();

        // Create and save a test AppDetails (used in GET tests)
        testAppDetails = AppDetails.builder()
                .appName("TEST_APP")
                .contextCluster("default")
                .contextNamespace("test-ns")
                .packageIdentifier("testrepo/test-app")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("1.0.0")
                .expectedInputData("test.input=${value}")
                .outputData(null)
                .requiredYamlConfiguration("{}")
                .yamlValueFieldType("PROPERTY")
                .build();
        testAppDetails = appRepository.save(testAppDetails);

        // Create and save a test AppServiceCatalog
        testCatalog = AppServiceCatalog.builder()
                .canonicalServiceId("test-catalog-id")
                .description("Test Catalog")
                .build();
        testCatalog = catalogRepository.save(testCatalog);

        // Create and save a test mapping
        testMapping = AppServiceCatalogAndCustomerMapping.builder()
                .serviceId("test-service-id")
                .canonicalId(testCatalog.getCanonicalServiceId())
                .customerId("test-customer")
                .build();
        testMapping = mappingRepository.save(testMapping);
    }

    @Test
    void shouldCreateOrUpdateAppInfo() throws Exception {
        AppDetailsRequest request = AppDetailsRequest.builder()
                .appName("NEW_APP")
                .contextCluster("default")
                .contextNamespace("new-ns")
                .packageIdentifier("newrepo/new-app")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("2.0.0")
                .expectedInputData("new.input=${value}")
                .outputData(null)
                .requiredYamlConfiguration("{}")
                .yamlValueFieldType("PROPERTY")
                .build();

        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("NEW_APP"))
                .andExpect(jsonPath("$.packageIdentifier").value("newrepo/new-app"))
                .andExpect(jsonPath("$.contextCluster").value("default"));

        // Verify that the entity is actually saved in the database
        AppDetails saved = appRepository.findByAppName("NEW_APP").orElse(null);
        assertNotNull(saved);
    }

    @Test
    void shouldGetAppInfoByAppName() throws Exception {
        mockMvc.perform(get("/internal/app-details/{appName}", "TEST_APP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("TEST_APP"))
                .andExpect(jsonPath("$.packageIdentifier").value("testrepo/test-app"));
    }

    @Test
    void shouldReturnNotFoundWhenGetAppInfoByAppNameNotFound() throws Exception {
        mockMvc.perform(get("/internal/app-details/{appName}", "NON_EXISTENT_APP"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldGetAllAppInfo() throws Exception {
        // Ensure we have at least one record (testAppDetails exists)
        mockMvc.perform(get("/internal/app-details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].appName").value("TEST_APP"));
    }

    @Test
    void shouldCreateCatalogService() throws Exception {
        AppServiceCatalogPojo pojo = AppServiceCatalogPojo.builder()
                .canonicalServiceId("new-catalog-id")
                .description("New Catalog")
                .build();

        String json = objectMapper.writeValueAsString(pojo);

        mockMvc.perform(post("/internal/catalog-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId").value("new-catalog-id"))
                .andExpect(jsonPath("$.description").value("New Catalog"));

        // Verify in DB
        AppServiceCatalog saved = catalogRepository.findByCanonicalServiceId("new-catalog-id").orElse(null);
        assertNotNull(saved);
    }

    @Test
    void shouldGetCatalogServiceById() throws Exception {
        mockMvc.perform(get("/internal/catalog-service/{id}", testCatalog.getCanonicalServiceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId").value(testCatalog.getCanonicalServiceId()))
                .andExpect(jsonPath("$.description").value(testCatalog.getDescription()));
    }

    @Test
    void shouldReturnNotFoundWhenGetCatalogServiceByIdNotFound() throws Exception {
        mockMvc.perform(get("/internal/catalog-service/{id}", "non-existent-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldGetAllCatalogService() throws Exception {
        mockMvc.perform(get("/internal/catalog-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].canonicalServiceId").value(testCatalog.getCanonicalServiceId()));
    }

    @Test
    void shouldCreateCatalogServiceMapping() throws Exception {
        // First create a new catalog to map
        AppServiceCatalog newCatalog = AppServiceCatalog.builder()
                .canonicalServiceId("mapping-catalog-id")
                .description("Mapping Catalog")
                .build();
        newCatalog = catalogRepository.save(newCatalog);

        AppServiceCatalogAndCustomerMappingPojo mappingPojo = AppServiceCatalogAndCustomerMappingPojo.builder()
                .serviceId("mapping-service-id")
                .canonicalId(newCatalog.getCanonicalServiceId())
                .customerId("mapping-customer")
                .build();

        String json = objectMapper.writeValueAsString(mappingPojo);

        mockMvc.perform(post("/internal/catalog-service-mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("mapping-service-id"))
                .andExpect(jsonPath("$.customerId").value("mapping-customer"))
                .andExpect(jsonPath("$.canonicalId").value(newCatalog.getCanonicalServiceId()));

        // Verify in DB
        AppServiceCatalogAndCustomerMapping saved = mappingRepository.findTop1ByServiceId("mapping-service-id");
        assertNotNull(saved);
        assertNotNull(saved.getServiceCatalog());
        assertEquals(newCatalog.getCanonicalServiceId(), saved.getServiceCatalog().getCanonicalServiceId());
    }

    @Test
    void shouldGetCatalogServiceMappingById() throws Exception {
        mockMvc.perform(get("/internal/catalog-service-mapping/{id}", testMapping.getServiceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(testMapping.getServiceId()))
                .andExpect(jsonPath("$.customerId").value(testMapping.getCustomerId()));
    }

    @Test
    void shouldReturnNotFoundWhenGetCatalogServiceMappingByIdNotFound() throws Exception {
        mockMvc.perform(get("/internal/catalog-service-mapping/{id}", "non-existent-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldGetAllCatalogServiceMapping() throws Exception {
        mockMvc.perform(get("/internal/catalog-service-mapping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].serviceId").value(testMapping.getServiceId()));
    }

    // Helper method to avoid repeated assert
    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: " + expected + " but was: " + actual);
        }
    }
}