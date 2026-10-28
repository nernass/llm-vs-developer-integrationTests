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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.transaction.Transactional;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Testcontainers
@Transactional
@DirtiesContext
class AppDetailsControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("autosetup")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

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

    private String validAppDetailsJson;
    private String validCatalogServiceJson;
    private String validMappingJson;

    @BeforeEach
    void setUp() throws Exception {
        // Clear all repositories
        appRepository.deleteAll();
        catalogRepository.deleteAll();
        mappingRepository.deleteAll();

        // Prepare JSON payloads
        validAppDetailsJson = """
                {
                    "appName": "EDC_DATAPLANE",
                    "contextCluster": "default",
                    "contextNamespace": "kubeapps",
                    "packageIdentifier": "edcrepo/edc-dataplane",
                    "pluginName": "helm.packages",
                    "pluginVersion": "v1alpha1",
                    "packageVersion": "0.1.1",
                    "expectedInputData": "edc.hostname=${dnsName}\\n\\tedc.vault.hashicorp.url=${vaulturl}",
                    "outputData": null,
                    "requiredYamlConfiguration": "{\\"ingresses\\":[{\\"enabled\\": true}]}",
                    "yamlValueFieldType": "PROPERTY"
                }
                """;

        validCatalogServiceJson = """
                {
                    "canonicalServiceId": "canonical-123",
                    "name": "EDC Dataplane",
                    "description": "EDC dataplane service",
                    "serviceType": "DATA_PLANE",
                    "image": "edc-dataplane:latest",
                    "port": 8080
                }
                """;

        validMappingJson = """
                {
                    "serviceId": "service-456",
                    "canonicalId": "canonical-123",
                    "customerId": "customer-789"
                }
                """;
    }

    // ==================== AppDetails Endpoints ====================

    @Test
    void createOrUpdateAppInfo_shouldReturnCreatedAppDetails() throws Exception {
        RequestBuilder request = MockMvcRequestBuilders
                .post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validAppDetailsJson);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        AppDetails created = objectMapper.readValue(response.getContentAsString(), AppDetails.class);
        assertNotNull(created);
        assertEquals("EDC_DATAPLANE", created.getAppName());
    }

    @Test
    void createOrUpdateAppInfo_shouldReturnBadRequest_whenInvalidInput() throws Exception {
        String invalidJson = """
                {
                    "appName": "",
                    "contextCluster": "default"
                }
                """;

        RequestBuilder request = MockMvcRequestBuilders
                .post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus());
    }

    @Test
    void getAppInfo_shouldReturnAppDetails_whenExists() throws Exception {
        // First create an app
        AppDetails app = createAppDetails("EDC_DATAPLANE");
        appRepository.save(app);

        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/app-details/EDC_DATAPLANE")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        AppDetails found = objectMapper.readValue(response.getContentAsString(), AppDetails.class);
        assertEquals(app.getAppName(), found.getAppName());
    }

    @Test
    void getAppInfo_shouldReturnNotFound_whenAppDoesNotExist() throws Exception {
        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/app-details/NON_EXISTENT")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(404, response.getStatus());
    }

    @Test
    void getAllAppInfo_shouldReturnListOfAppDetails() throws Exception {
        // Insert two apps
        appRepository.save(createAppDetails("APP1"));
        appRepository.save(createAppDetails("APP2"));

        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/app-details")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        List<AppDetails> list = objectMapper.readValue(
                response.getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, AppDetails.class));
        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(a -> "APP1".equals(a.getAppName())));
        assertTrue(list.stream().anyMatch(a -> "APP2".equals(a.getAppName())));
    }

    // ==================== CatalogService Endpoints ====================

    @Test
    void createCatalogService_shouldReturnCreatedCatalog() throws Exception {
        RequestBuilder request = MockMvcRequestBuilders
                .post("/internal/catalog-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCatalogServiceJson);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        AppServiceCatalog created = objectMapper.readValue(response.getContentAsString(), AppServiceCatalog.class);
        assertNotNull(created);
        assertEquals("canonical-123", created.getCanonicalServiceId());
    }

    @Test
    void createCatalogService_shouldReturnBadRequest_whenInvalidInput() throws Exception {
        String invalidJson = """
                {
                    "canonicalServiceId": ""
                }
                """;

        RequestBuilder request = MockMvcRequestBuilders
                .post("/internal/catalog-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus());
    }

    @Test
    void getCatalogService_shouldReturnCatalog_whenExists() throws Exception {
        AppServiceCatalog catalog = createCatalog("canonical-123");
        catalogRepository.save(catalog);

        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/catalog-service/canonical-123")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        AppServiceCatalog found = objectMapper.readValue(response.getContentAsString(), AppServiceCatalog.class);
        assertEquals(catalog.getCanonicalServiceId(), found.getCanonicalServiceId());
    }

    @Test
    void getCatalogService_shouldReturnNotFound_whenCatalogDoesNotExist() throws Exception {
        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/catalog-service/non-existent")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(404, response.getStatus());
    }

    @Test
    void getAllCatalogService_shouldReturnListOfCatalogs() throws Exception {
        catalogRepository.save(createCatalog("cat1"));
        catalogRepository.save(createCatalog("cat2"));

        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/catalog-service")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        List<AppServiceCatalog> list = objectMapper.readValue(
                response.getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, AppServiceCatalog.class));
        assertEquals(2, list.size());
    }

    // ==================== CatalogServiceMapping Endpoints ====================

    @Test
    void createCatalogServiceMapping_shouldReturnCreatedMapping() throws Exception {
        // Pre-requisite: catalog must exist
        catalogRepository.save(createCatalog("canonical-123"));

        RequestBuilder request = MockMvcRequestBuilders
                .post("/internal/catalog-service-mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validMappingJson);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        AppServiceCatalogAndCustomerMapping created = objectMapper.readValue(
                response.getContentAsString(), AppServiceCatalogAndCustomerMapping.class);
        assertNotNull(created);
        assertEquals("service-456", created.getServiceId());
        assertNotNull(created.getServiceCatalog()); // should have been set from catalog
        assertEquals("canonical-123", created.getServiceCatalog().getCanonicalServiceId());
    }

    @Test
    void createCatalogServiceMapping_shouldReturnBadRequest_whenInvalidInput() throws Exception {
        String invalidJson = """
                {
                    "serviceId": ""
                }
                """;

        RequestBuilder request = MockMvcRequestBuilders
                .post("/internal/catalog-service-mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus());
    }

    @Test
    void getCatalogServiceMapping_shouldReturnMapping_whenExists() throws Exception {
        // Pre-requisite: catalog and mapping
        catalogRepository.save(createCatalog("canonical-123"));
        AppServiceCatalogAndCustomerMapping mapping = createMapping("service-456", "canonical-123", "customer-789");
        mappingRepository.save(mapping);

        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/catalog-service-mapping/service-456")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        AppServiceCatalogAndCustomerMapping found = objectMapper.readValue(
                response.getContentAsString(), AppServiceCatalogAndCustomerMapping.class);
        assertNotNull(found);
        assertEquals("service-456", found.getServiceId());
    }

    @Test
    void getCatalogServiceMapping_shouldReturnNotFound_whenMappingDoesNotExist() throws Exception {
        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/catalog-service-mapping/non-existent")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        // If no mapping found, service returns null, controller may return null (HTTP 200 with empty body)
        // We need to check what the actual behavior is; in the service, getCatalogServiceMapping returns null if not found.
        // The controller returns that object directly, so response will be 200 with null body.
        // We can test that the response body is empty or null.
        assertEquals(200, response.getStatus());
        String content = response.getContentAsString();
        assertTrue(content.isEmpty() || "null".equals(content));
    }

    @Test
    void getAllCatalogServiceMapping_shouldReturnListOfMappings() throws Exception {
        catalogRepository.save(createCatalog("canonical-123"));
        catalogRepository.save(createCatalog("canonical-456"));

        mappingRepository.save(createMapping("service-1", "canonical-123", "cust1"));
        mappingRepository.save(createMapping("service-2", "canonical-456", "cust2"));

        RequestBuilder request = MockMvcRequestBuilders
                .get("/internal/catalog-service-mapping")
                .accept(MediaType.APPLICATION_JSON);

        MvcResult result = mockMvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus());
        List<AppServiceCatalogAndCustomerMapping> list = objectMapper.readValue(
                response.getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, AppServiceCatalogAndCustomerMapping.class));
        assertEquals(2, list.size());
    }

    // ==================== Helper methods ====================

    private AppDetails createAppDetails(String appName) {
        AppDetails details = new AppDetails();
        details.setAppName(appName);
        details.setContextCluster("default");
        details.setContextNamespace("kubeapps");
        details.setPackageIdentifier("repo/" + appName);
        details.setPluginName("helm.packages");
        details.setPluginVersion("v1alpha1");
        details.setPackageVersion("0.1.0");
        details.setExpectedInputData("input");
        details.setRequiredYamlConfiguration("{}");
        details.setYamlValueFieldType("PROPERTY");
        return details;
    }

    private AppServiceCatalog createCatalog(String canonicalId) {
        AppServiceCatalog catalog = new AppServiceCatalog();
        catalog.setCanonicalServiceId(canonicalId);
        catalog.setName("Service " + canonicalId);
        catalog.setDescription("Desc");
        catalog.setServiceType("TYPE");
        catalog.setImage("image");
        catalog.setPort(8080);
        return catalog;
    }

    private AppServiceCatalogAndCustomerMapping createMapping(String serviceId, String canonicalId, String customerId) {
        AppServiceCatalogAndCustomerMapping mapping = new AppServiceCatalogAndCustomerMapping();
        mapping.setServiceId(serviceId);
        mapping.setCanonicalId(canonicalId);
        mapping.setCustomerId(customerId);
        return mapping;
    }
}