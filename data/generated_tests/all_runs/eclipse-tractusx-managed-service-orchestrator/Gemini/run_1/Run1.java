
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.tractusx.autosetup.entity.AppDetails;
import org.eclipse.tractusx.autosetup.entity.AppServiceCatalog;
import org.eclipse.tractusx.autosetup.entity.AppServiceCatalogAndCustomerMapping;
import org.eclipse.tractusx.autosetup.exception.NoDataFoundException;
import org.eclipse.tractusx.autosetup.model.AppDetailsRequest;
import org.eclipse.tractusx.autosetup.model.AppServiceCatalogAndCustomerMappingPojo;
import org.eclipse.tractusx.autosetup.model.AppServiceCatalogPojo;
import org.eclipse.tractusx.autosetup.service.AppDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ActiveProfiles("test")
class AppDetailsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppDetailsService appDetailsService;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /internal/app-details
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenCreateOrUpdateAppInfoWithValidRequest() throws Exception {
        when(appDetailsService.createOrUpdateAppInfo(any(AppDetailsRequest.class)))
                .thenReturn(buildSampleAppDetails());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(buildValidAppDetailsRequestJson()))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("EDC_DATAPLANE"));
        assertTrue(response.getContentAsString().contains("helm.packages"));
        verify(appDetailsService, times(1)).createOrUpdateAppInfo(any(AppDetailsRequest.class));
    }

    @Test
    void shouldReturn200AndResponseBodyReflectsServiceOutput() throws Exception {
        when(appDetailsService.createOrUpdateAppInfo(any(AppDetailsRequest.class)))
                .thenReturn(buildSampleAppDetails());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(buildValidAppDetailsRequestJson()))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"appName\":\"EDC_DATAPLANE\""));
        assertTrue(body.contains("\"packageVersion\":\"0.1.1\""));
        assertTrue(body.contains("\"pluginVersion\":\"v1alpha1\""));
        assertTrue(body.contains("\"contextCluster\":\"default\""));
    }

    @Test
    void shouldReturn400WhenCreateOrUpdateAppInfoWithEmptyJsonBody() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
        verify(appDetailsService, times(0)).createOrUpdateAppInfo(any(AppDetailsRequest.class));
    }

    @Test
    void shouldReturn400WhenCreateOrUpdateAppInfoWithMissingRequestBody() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
        verify(appDetailsService, times(0)).createOrUpdateAppInfo(any(AppDetailsRequest.class));
    }

    @Test
    void shouldReturn400WhenCreateOrUpdateAppInfoWithBlankAppName() throws Exception {
        String request = "{\n" +
                "  \"appName\": \"\",\n" +
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

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
        verify(appDetailsService, times(0)).createOrUpdateAppInfo(any(AppDetailsRequest.class));
    }

    @Test
    void shouldReturn400WhenCreateOrUpdateAppInfoWithInvalidAppNameContainingSpecialChars() throws Exception {
        String request = "{\n" +
                "  \"appName\": \"invalid app!\",\n" +
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

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
        verify(appDetailsService, times(0)).createOrUpdateAppInfo(any(AppDetailsRequest.class));
    }

    @Test
    void shouldReturn400WhenCreateOrUpdateAppInfoWithMissingPackageIdentifier() throws Exception {
        String request = "{\n" +
                "  \"appName\": \"EDC_DATAPLANE\",\n" +
                "  \"contextCluster\": \"default\",\n" +
                "  \"contextNamespace\": \"kubeapps\",\n" +
                "  \"pluginName\": \"helm.packages\",\n" +
                "  \"pluginVersion\": \"v1alpha1\",\n" +
                "  \"packageVersion\": \"0.1.1\",\n" +
                "  \"expectedInputData\": \"edc.hostname=${dnsName}\",\n" +
                "  \"requiredYamlConfiguration\": \"{\\\"key\\\": \\\"value\\\"}\",\n" +
                "  \"yamlValueFieldType\": \"PROPERTY\"\n" +
                "}";

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
        verify(appDetailsService, times(0)).createOrUpdateAppInfo(any(AppDetailsRequest.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/app-details/{appName}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenGetAppInfoWithExistingAppName() throws Exception {
        when(appDetailsService.getAppDetails("EDC_DATAPLANE"))
                .thenReturn(buildSampleAppDetails());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/app-details/EDC_DATAPLANE")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("EDC_DATAPLANE"));
        verify(appDetailsService, times(1)).getAppDetails("EDC_DATAPLANE");
    }

    @Test
    void shouldReturn404WhenGetAppInfoWithNonExistentAppName() throws Exception {
        when(appDetailsService.getAppDetails("UNKNOWN_APP"))
                .thenThrow(new NoDataFoundException("No data found for UNKNOWN_APP"));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/app-details/UNKNOWN_APP")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("No data found for UNKNOWN_APP"));
        verify(appDetailsService, times(1)).getAppDetails("UNKNOWN_APP");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/app-details
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WithPopulatedListWhenGetAllAppInfo() throws Exception {
        List<AppDetails> appDetailsList = Arrays.asList(
                buildSampleAppDetails(),
                buildAnotherAppDetails());
        when(appDetailsService.getAppDetails()).thenReturn(appDetailsList);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/app-details")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("EDC_DATAPLANE"));
        assertTrue(body.contains("EDC_CONTROLPLANE"));
        verify(appDetailsService, times(1)).getAppDetails();
    }

    @Test
    void shouldReturn200WithEmptyListWhenNoAppsExist() throws Exception {
        when(appDetailsService.getAppDetails()).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/app-details")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals("[]", response.getContentAsString());
        verify(appDetailsService, times(1)).getAppDetails();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /internal/catalog-service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenCreateCatalogServiceWithValidRequest() throws Exception {
        when(appDetailsService.createCatalogService(any(AppServiceCatalogPojo.class)))
                .thenReturn(buildSampleCatalog());

        String requestJson = "{\n" +
                "  \"canonicalServiceId\": \"DT-REGISTRY\",\n" +
                "  \"name\": \"DT-Registry\",\n" +
                "  \"workflow\": \"DT_REGISTRY\",\n" +
                "  \"serviceTools\": \"{\\\"name\\\": \\\"tool1\\\"}\"\n" +
                "}";

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("DT-REGISTRY"));
        verify(appDetailsService, times(1)).createCatalogService(any(AppServiceCatalogPojo.class));
    }

    @Test
    void shouldReturn400WhenCreateCatalogServiceWithMissingRequiredFields() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
        verify(appDetailsService, times(0)).createCatalogService(any(AppServiceCatalogPojo.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/catalog-service/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenGetCatalogServiceWithExistingId() throws Exception {
        when(appDetailsService.getCatalogService("DT-REGISTRY"))
                .thenReturn(buildSampleCatalog());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/catalog-service/DT-REGISTRY")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("DT-REGISTRY"));
        verify(appDetailsService, times(1)).getCatalogService("DT-REGISTRY");
    }

    @Test
    void shouldReturn404WhenGetCatalogServiceWithNonExistentId() throws Exception {
        when(appDetailsService.getCatalogService("UNKNOWN_SERVICE"))
                .thenThrow(new NoDataFoundException("No data found for UNKNOWN_SERVICE"));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/catalog-service/UNKNOWN_SERVICE")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("No data found for UNKNOWN_SERVICE"));
        verify(appDetailsService, times(1)).getCatalogService("UNKNOWN_SERVICE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/catalog-service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WithCatalogListWhenGetAllCatalogService() throws Exception {
        when(appDetailsService.getAllCatalogService())
                .thenReturn(Collections.singletonList(buildSampleCatalog()));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/catalog-service")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("DT-REGISTRY"));
        verify(appDetailsService, times(1)).getAllCatalogService();
    }

    @Test
    void shouldReturn200WithEmptyListWhenNoCatalogServicesExist() throws Exception {
        when(appDetailsService.getAllCatalogService()).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/catalog-service")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals("[]", response.getContentAsString());
        verify(appDetailsService, times(1)).getAllCatalogService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /internal/catalog-service-mapping
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenCreateCatalogServiceMappingWithValidRequest() throws Exception {
        when(appDetailsService.createCatalogServiceMapping(any(AppServiceCatalogAndCustomerMappingPojo.class)))
                .thenReturn(buildSampleMapping());

        String requestJson = "{\n" +
                "  \"customer\": \"customer-001\",\n" +
                "  \"serviceId\": \"service-001\",\n" +
                "  \"canonicalId\": \"DT-REGISTRY\",\n" +
                "  \"serviceCatalog\": {\n" +
                "    \"canonicalServiceId\": \"DT-REGISTRY\",\n" +
                "    \"name\": \"DT-Registry\",\n" +
                "    \"workflow\": \"DT_REGISTRY\",\n" +
                "    \"serviceTools\": \"{\\\"name\\\": \\\"tool1\\\"}\"\n" +
                "  }\n" +
                "}";

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        verify(appDetailsService, times(1))
                .createCatalogServiceMapping(any(AppServiceCatalogAndCustomerMappingPojo.class));
    }

    @Test
    void shouldReturn400WhenCreateCatalogServiceMappingWithMissingRequiredFields() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.getResponse().getStatus());
        verify(appDetailsService, times(0))
                .createCatalogServiceMapping(any(AppServiceCatalogAndCustomerMappingPojo.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/catalog-service-mapping/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturn200WhenGetCatalogServiceMappingWithExistingServiceId() throws Exception {
        when(appDetailsService.getCatalogServiceMapping("service-001"))
                .thenReturn(buildSampleMapping());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/catalog-service-mapping/service-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        verify(appDetailsService, times(1)).getCatalogServiceMapping("service-001");
    }

    @Test
    void shouldReturn200WithNullBodyWhenGetCatalogServiceMappingNotFound() throws Exception {
        when(appDetailsService.getCatalogServiceMapping("missing-service")).thenReturn(null);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/internal/catalog-service-mapping/missing-service")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        assertEquals(HttpStatus.OK.value(), result.getResponse().getStatus());
        verify(appDetailsService, times(1)).getCatalogServiceMapping("missing-service");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildValidAppDetailsRequestJson() {
        return "{\n" +
                "  \"appName\": \"EDC_DATAPLANE\",\n" +
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

    private AppDetails buildSampleAppDetails() {
        return AppDetails.builder()
                .appName("EDC_DATAPLANE")
                .contextCluster("default")
                .contextNamespace("kubeapps")
                .packageIdentifier("edcrepo/edc-dataplane")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("0.1.1")
                .expectedInputData("edc.hostname=${dnsName}")
                .requiredYamlConfiguration("{\"key\": \"value\"}")
                .yamlValueFieldType("PROPERTY")
                .build();
    }

    private AppDetails buildAnotherAppDetails() {
        return AppDetails.builder()
                .appName("EDC_CONTROLPLANE")
                .contextCluster("default")
                .contextNamespace("kubeapps")
                .packageIdentifier("edcrepo/edc-controlplane")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("0.1.1")
                .expectedInputData("edc.hostname=${dnsName}")
                .requiredYamlConfiguration("{\"key\": \"value\"}")
                .yamlValueFieldType("PROPERTY")
                .build();
    }

    private AppServiceCatalog buildSampleCatalog() {
        return new AppServiceCatalog(
                "DT-REGISTRY",
                "DT-Registry",
                "DT_REGISTRY",
                "{\"name\": \"tool1\"}");
    }

    private AppServiceCatalogAndCustomerMapping buildSampleMapping() {
        return AppServiceCatalogAndCustomerMapping.builder()
                .customer("customer-001")
                .serviceId("service-001")
                .serviceCatalog(buildSampleCatalog())
                .build();
    }
}
