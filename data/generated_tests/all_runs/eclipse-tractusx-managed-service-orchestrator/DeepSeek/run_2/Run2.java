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

import org.eclipse.tractusx.autosetup.entity.AppDetails;
import org.eclipse.tractusx.autosetup.entity.AppServiceCatalog;
import org.eclipse.tractusx.autosetup.entity.AppServiceCatalogAndCustomerMapping;
import org.eclipse.tractusx.autosetup.model.AppDetailsRequest;
import org.eclipse.tractusx.autosetup.model.AppServiceCatalogAndCustomerMappingPojo;
import org.eclipse.tractusx.autosetup.model.AppServiceCatalogPojo;
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
class AppDetailsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String validAppDetailsRequest = """
            {
              "appName": "EDC_DATAPLANE",
              "contextCluster": "default",
              "contextNamespace": "kubeapps",
              "packageIdentifier": "edcrepo/edc-dataplane",
              "pluginName": "helm.packages",
              "pluginVersion": "v1alpha1",
              "packageVersion": "0.1.1",
              "expectedInputData": "edc.hostname=${dnsName}\\n\\t\\nedc.vault.hashicorp.url=${vaulturl}\\n\\nedc.vault.hashicorp.token=${vaulttoken}\\n\\nedc.vault.hashicorp.timeout.seconds=${vaulttimeout}\\n\\nedc.dataplane.token.validation.endpoint=${controlPlaneValidationEndpoint}",
              "outputData": null,
              "requiredYamlConfiguration": "{\\"ingresses\\":[{\\"enabled\\": true, \\"hostname\\": \\"${dnsName}\\", \\"annotations\\": {}, \\"className\\": \\"nginx\\", \\"endpoints\\":[\\"public\\"], \\"tls\\":{\\"enabled\\": true, \\"secretName\\":\\"edcdataplane\\"},\\"certManager\\":{\\"clusterIssuer\\":\\"letsencrypt-staging\\"}}], \\"configuration\\": {\\"properties\\": \\"${yamlValues}\\"}}",
              "yamlValueFieldType": "PROPERTY"
            }
            """;

    private final String validCatalogServicePojo = """
            {
              "canonicalServiceId": "canonical-123",
              "serviceId": "service-123",
              "serviceName": "My Service",
              "serviceDescription": "A test service",
              "serviceUrl": "http://service.url"
            }
            """;

    private final String validCatalogMappingPojo = """
            {
              "serviceId": "service-123",
              "canonicalId": "canonical-123",
              "customerId": "customer-123"
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        // Ensure that we start with an empty database for each test
        // @Transactional will rollback after each test, so no explicit cleanup needed
        // but we can pre-populate data if required for specific tests.
        // For this test class, we will create data inside each test method as needed.
    }

    @Test
    void createOrUpdateAppInfo_shouldReturnCreatedAppDetails() throws Exception {
        String response = mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAppDetailsRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("EDC_DATAPLANE"))
                .andExpect(jsonPath("$.packageIdentifier").value("edcrepo/edc-dataplane"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        AppDetails created = objectMapper.readValue(response, AppDetails.class);
        assertNotNull(created);
    }

    @Test
    void createOrUpdateAppInfo_withInvalidRequest_shouldReturnBadRequest() throws Exception {
        String invalidRequest = """
                {
                  "appName": "",
                  "contextCluster": "default"
                }
                """;

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAppInfo_existingApp_shouldReturnDetails() throws Exception {
        // First create an app
        String createResponse = mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAppDetailsRequest))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        AppDetails created = objectMapper.readValue(createResponse, AppDetails.class);
        String appName = created.getAppName();

        // Then retrieve it
        mockMvc.perform(get("/internal/app-details/{appName}", appName)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value(appName))
                .andExpect(jsonPath("$.packageIdentifier").value("edcrepo/edc-dataplane"));
    }

    @Test
    void getAppInfo_nonExistingApp_shouldReturnNotFound() throws Exception {
        mockMvc.perform(get("/internal/app-details/non-existing-app")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAllAppInfo_shouldReturnList() throws Exception {
        // Create two apps
        for (int i = 0; i < 2; i++) {
            String request = validAppDetailsRequest.replace("EDC_DATAPLANE", "APP_" + i);
            mockMvc.perform(post("/internal/app-details")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void createCatalogService_shouldReturnCreatedService() throws Exception {
        String response = mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCatalogServicePojo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId").value("canonical-123"))
                .andExpect(jsonPath("$.serviceId").value("service-123"))
                .andExpect(jsonPath("$.serviceName").value("My Service"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        AppServiceCatalog created = objectMapper.readValue(response, AppServiceCatalog.class);
        assertNotNull(created);
    }

    @Test
    void createCatalogService_withInvalidRequest_shouldReturnBadRequest() throws Exception {
        String invalid = """
                {
                  "canonicalServiceId": "",
                  "serviceId": ""
                }
                """;
        mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCatalogService_existingId_shouldReturnService() throws Exception {
        // Create
        String response = mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCatalogServicePojo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        AppServiceCatalog created = objectMapper.readValue(response, AppServiceCatalog.class);
        String id = created.getCanonicalServiceId();

        // Retrieve
        mockMvc.perform(get("/internal/catalog-service/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId").value(id))
                .andExpect(jsonPath("$.serviceName").value("My Service"));
    }

    @Test
    void getCatalogService_nonExistingId_shouldReturnNotFound() throws Exception {
        mockMvc.perform(get("/internal/catalog-service/non-existing")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAllCatalogService_shouldReturnList() throws Exception {
        // Create two
        for (int i = 0; i < 2; i++) {
            String request = validCatalogServicePojo.replace("canonical-123", "canonical-" + i)
                    .replace("service-123", "service-" + i)
                    .replace("My Service", "Service " + i);
            mockMvc.perform(post("/internal/catalog-service")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void createCatalogServiceMapping_shouldReturnMapping() throws Exception {
        // First create catalog service
        String catalogResponse = mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCatalogServicePojo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        AppServiceCatalog catalog = objectMapper.readValue(catalogResponse, AppServiceCatalog.class);

        // Then create mapping
        String mappingRequest = """
                {
                  "serviceId": "service-123",
                  "canonicalId": "canonical-123",
                  "customerId": "customer-123"
                }
                """;

        mockMvc.perform(post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("service-123"))
                .andExpect(jsonPath("$.canonicalId").value("canonical-123"))
                .andExpect(jsonPath("$.customerId").value("customer-123"));
    }

    @Test
    void createCatalogServiceMapping_withNonExistingCanonicalId_shouldThrowException() throws Exception {
        // Attempt mapping without existing catalog service
        String mappingRequest = """
                {
                  "serviceId": "service-123",
                  "canonicalId": "non-existing",
                  "customerId": "customer-123"
                }
                """;

        mockMvc.perform(post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingRequest))
                .andExpect(status().isNotFound()); // NoDataFoundException thrown from service
    }

    @Test
    void getCatalogServiceMapping_existingId_shouldReturnMapping() throws Exception {
        // Create catalog service
        mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCatalogServicePojo))
                .andExpect(status().isOk());

        // Create mapping
        String mappingRequest = """
                {
                  "serviceId": "service-123",
                  "canonicalId": "canonical-123",
                  "customerId": "customer-123"
                }
                """;
        mockMvc.perform(post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingRequest))
                .andExpect(status().isOk());

        // Retrieve
        mockMvc.perform(get("/internal/catalog-service-mapping/service-123")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("service-123"))
                .andExpect(jsonPath("$.customerId").value("customer-123"));
    }

    @Test
    void getCatalogServiceMapping_nonExistingId_shouldReturnEmptyBody() throws Exception {
        // No mapping exists for this id
        mockMvc.perform(get("/internal/catalog-service-mapping/non-existing")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist()); // or expects null?
    }

    @Test
    void getAllCatalogServiceMapping_shouldReturnList() throws Exception {
        // Create catalog service
        mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCatalogServicePojo))
                .andExpect(status().isOk());

        // Create two mappings
        for (int i = 0; i < 2; i++) {
            String mappingRequest = """
                    {
                      "serviceId": "service-%d",
                      "canonicalId": "canonical-123",
                      "customerId": "customer-%d"
                    }
                    """.formatted(i, i);
            mockMvc.perform(post("/internal/catalog-service-mapping")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mappingRequest))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}