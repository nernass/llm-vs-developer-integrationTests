
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ActiveProfiles("test")
@Transactional
class AppDetailsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /internal/app-details
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenCreateOrUpdateAppInfoWithValidRequest() throws Exception {
        String request = buildValidAppDetailsRequestJson("EDC_DATAPLANE");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(request))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("EDC_DATAPLANE"));
        assertTrue(response.getContentAsString().contains("helm.packages"));
    }

    @Test
    void shouldReturn400WhenCreateOrUpdateAppInfoWithMissingRequiredFields() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
    }

    @Test
    void shouldReturn400WhenCreateOrUpdateAppInfoWithInvalidAppName() throws Exception {
        String request = buildValidAppDetailsRequestJson("invalid app name!");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(request))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/app-details/{appName}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenGetAppInfoWithExistingAppName() throws Exception {
        // Seed database
        String appName = "TEST_APP";
        mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildValidAppDetailsRequestJson(appName)));

        // Retrieve saved app
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/internal/app-details/" + appName)
                .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains(appName));
    }

    @Test
    void shouldReturn404WhenGetAppInfoWithNonExistentAppName() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/internal/app-details/UNKNOWN_APP")
                .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("No data found for UNKNOWN_APP"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/app-details
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WithAppListWhenGetAllAppInfo() throws Exception {
        // Seed multiple apps
        mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildValidAppDetailsRequestJson("APP_ONE")));

        mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildValidAppDetailsRequestJson("APP_TWO")));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/internal/app-details")
                .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("APP_ONE"));
        assertTrue(body.contains("APP_TWO"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /internal/catalog-service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenCreateCatalogServiceWithValidRequest() throws Exception {
        String requestJson = buildValidCatalogServiceJson("DT-REGISTRY");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/catalog-service")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("DT-REGISTRY"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/catalog-service/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenGetCatalogServiceWithExistingId() throws Exception {
        // Seed catalog
        String serviceId = "EXISTING-SERVICE";
        mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/catalog-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildValidCatalogServiceJson(serviceId)));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/internal/catalog-service/" + serviceId)
                .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains(serviceId));
    }

    @Test
    void shouldReturn404WhenGetCatalogServiceWithNonExistentId() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/internal/catalog-service/MISSING-SERVICE")
                .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/catalog-service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WithCatalogListWhenGetAllCatalogService() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/catalog-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildValidCatalogServiceJson("CATALOG-1")));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/internal/catalog-service")
                .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("CATALOG-1"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /internal/catalog-service-mapping
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenCreateCatalogServiceMappingWithValidRequest() throws Exception {
        String requestJson = buildValidCatalogMappingJson("customer-001", "service-001", "CATALOG-MAPPING-1");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/catalog-service-mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("customer-001"));
        assertTrue(response.getContentAsString().contains("service-001"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/catalog-service-mapping/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenGetCatalogServiceMappingWithExistingServiceId() throws Exception {
        // Seed mapping
        String serviceId = "mapped-service-123";
        mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/catalog-service-mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content(buildValidCatalogMappingJson("cust-123", serviceId, "CAT-123")));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .get("/internal/catalog-service-mapping/" + serviceId)
                .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains(serviceId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildValidAppDetailsRequestJson(String appName) {
        return "{\n" +
                "  \"appName\": \"" + appName + "\",\n" +
                "  \"contextCluster\": \"default\",\n" +
                "  \"contextNamespace\": \"kubeapps\",\n" +
                "  \"packageIdentifier\": \"edcrepo/edc-dataplane\",\n" +
                "  \"pluginName\": \"helm.packages\",\n" +
                "  \"pluginVersion\": \"v1alpha1\",\n" +
                "  \"packageVersion\": \"0.1.1\",\n" +
                "  \"expectedInputData\": \"edc.hostname=${dnsName}\",\n" +
                "  \"requiredYamlConfiguration\": \"{\\\"key\\\": \\\"value\\\"}\",\n" +
                "  \"yamlValueFieldType\": \"PROPERTY\"\n" +
                "}";
    }

    private String buildValidCatalogServiceJson(String canonicalId) {
        return "{\n" +
                "  \"canonicalServiceId\": \"" + canonicalId + "\",\n" +
                "  \"name\": \"Service Name\",\n" +
                "  \"workflow\": \"WORKFLOW_A\",\n" +
                "  \"serviceTools\": \"{\\\"tool\\\": \\\"value\\\"}\"\n" +
                "}";
    }

    private String buildValidCatalogMappingJson(String customer, String serviceId, String canonicalId) {
        return "{\n" +
                "  \"customer\": \"" + customer + "\",\n" +
                "  \"serviceId\": \"" + serviceId + "\",\n" +
                "  \"canonicalId\": \"" + canonicalId + "\",\n" +
                "  \"serviceCatalog\": " + buildValidCatalogServiceJson(canonicalId) + "\n" +
                "}";
    }
}
