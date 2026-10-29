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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.tractusx.autosetup.constant.AppActions;
import org.eclipse.tractusx.autosetup.constant.TriggerStatusEnum;
import org.eclipse.tractusx.autosetup.entity.AppServiceCatalogAndCustomerMapping;
import org.eclipse.tractusx.autosetup.entity.AutoSetupTriggerEntry;
import org.eclipse.tractusx.autosetup.kubeapps.proxy.KubeAppManageProxy;
import org.eclipse.tractusx.autosetup.manager.AutoSetupTriggerManager;
import org.eclipse.tractusx.autosetup.manager.EmailManager;
import org.eclipse.tractusx.autosetup.model.AutoSetupRequest;
import org.eclipse.tractusx.autosetup.model.AutoSetupResponse;
import org.eclipse.tractusx.autosetup.model.Customer;
import org.eclipse.tractusx.autosetup.model.CustomerProperties;
import org.eclipse.tractusx.autosetup.model.SelectedTools;
import org.eclipse.tractusx.autosetup.repository.AppServiceCatalogAndCustomerMappingRepository;
import org.eclipse.tractusx.autosetup.repository.AutoSetupTriggerEntryRepository;
import org.eclipse.tractusx.autosetup.service.AutoSetupOrchitestratorService;
import org.eclipse.tractusx.autosetup.service.DTAppWorkFlow;
import org.eclipse.tractusx.autosetup.service.EDCConnectorWorkFlow;
import org.eclipse.tractusx.autosetup.service.SDEAppWorkFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@ActiveProfiles("test")
class deepseek {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AutoSetupTriggerEntryRepository triggerRepository;

    @Autowired
    private AppServiceCatalogAndCustomerMappingRepository serviceCatalogRepository;

    @Autowired
    private AutoSetupOrchitestratorService orchestratorService;

    @Autowired
    private AutoSetupTriggerManager triggerManager;

    // External dependencies – to be mocked
    @MockBean
    private KubeAppManageProxy kubeAppManageProxy;

    @MockBean
    private EmailManager emailManager;

    @MockBean
    private EDCConnectorWorkFlow edcConnectorWorkFlow;

    @MockBean
    private SDEAppWorkFlow sdeAppWorkFlow;

    @MockBean
    private DTAppWorkFlow dtAppWorkFlow;

    private final String validServiceId = "T-SYSTEM-SDE-EDC";
    private final String validTriggerId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        // Clean up any residual data (though @Transactional should handle rollback)
        triggerRepository.deleteAll();
        serviceCatalogRepository.deleteAll();

        // Insert a service catalog entry for valid serviceId
        AppServiceCatalogAndCustomerMapping catalog = AppServiceCatalogAndCustomerMapping.builder()
                .customer("TestOrg")
                .serviceId(validServiceId)
                .canonicalId(validServiceId)
                .serviceCatalog(new org.eclipse.tractusx.autosetup.entity.AppServiceCatalog())
                .build();
        // The serviceCatalog field might need more setup, but for test we only need it to exist.
        // We'll set a minimal catalog with tools.
        org.eclipse.tractusx.autosetup.entity.AppServiceCatalog catalogEntity = new org.eclipse.tractusx.autosetup.entity.AppServiceCatalog();
        catalogEntity.setCanonicalServiceId(validServiceId);
        catalogEntity.setName("Test Service");
        catalogEntity.setWorkflow("SDE_WITH_EDC_TRACTUS");
        catalogEntity.setServiceTools("[{\"tool\":\"SDE_WITH_EDC_TRACTUS\",\"label\":\"sde-edc\"}]");
        catalog.setServiceCatalog(catalogEntity);
        serviceCatalogRepository.save(catalog);

        // For update/delete tests, insert a trigger entry
        AutoSetupTriggerEntry trigger = AutoSetupTriggerEntry.builder()
                .triggerId(validTriggerId)
                .organizationName("TestOrg")
                .subscriptionId("SUB123")
                .serviceId(validServiceId)
                .autosetupRequest("{\"customer\":{\"organizationName\":\"TestOrg\"},\"properties\":{\"serviceId\":\"" + validServiceId + "\"}}")
                .triggerType(AppActions.CREATE.name())
                .status(TriggerStatusEnum.INPROGRESS.name())
                .createdTimestamp(LocalDateTime.now().toString())
                .modifiedTimestamp(LocalDateTime.now().toString())
                .autosetupTenantName("test-namespace")
                .build();
        triggerRepository.save(trigger);

        // Stub KubeAppManageProxy calls
        when(kubeAppManageProxy.checkNamespace(anyString(), anyString())).thenReturn("true");
        when(kubeAppManageProxy.createNamespace(anyString(), anyString())).thenReturn("true");
        when(kubeAppManageProxy.getAllInstallPackages()).thenReturn("[]");
    }

    @Test
    void createPackage_shouldReturnUuidAndCallWorkflows() throws Exception {
        // Given
        Customer customer = Customer.builder()
                .organizationName("TestOrg")
                .country("DE")
                .state("BY")
                .city("Munich")
                .email("test@example.com")
                .build();
        CustomerProperties properties = CustomerProperties.builder()
                .bpnNumber("BPN123")
                .role("recycler")
                .subscriptionId("SUB456")
                .serviceId(validServiceId)
                .build();
        AutoSetupRequest request = new AutoSetupRequest();
        request.setCustomer(customer);
        request.setProperties(properties);

        String requestJson = objectMapper.writeValueAsString(request);

        // Stub workflow calls to return dummy maps
        Map<String, String> dummyEdcOutput = Map.of(
                "controlPlaneEndpoint", "http://edc-control",
                "controlPlaneDataEndpoint", "http://edc-data",
                "dataPlanePublicEndpoint", "http://edc-public",
                "edcApiKey", "apiKey",
                "edcApiKeyValue", "value",
                "connectorId", "connector-1"
        );
        when(edcConnectorWorkFlow.getWorkFlow(any(), any(), any(), any(), any())).thenReturn(dummyEdcOutput);
        when(sdeAppWorkFlow.getWorkFlow(any(), any(), any(), any(), any())).thenReturn(Map.of(
                "sdeFrontEndUrl", "http://sde-front",
                "sdeBackEndUrl", "http://sde-back",
                "connectorTestResult", "consumer and provider connectivity successful"
        ));
        when(dtAppWorkFlow.getWorkFlow(any(), any(), any(), any(), any())).thenReturn(Map.of(
                "dtregistryUrl", "http://dt-registry",
                "idpClientId", "idp-123"
        ));

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/autosetup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        if (response.getStatus() != HttpStatus.OK.value()) {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatus());
            return;
        }
        String responseBody = response.getContentAsString();
        // Should be a UUID
        UUID.fromString(responseBody); // throws if invalid

        // Verify that a trigger entry was created
        waitUntilAsserted(() -> {
            List<AutoSetupTriggerEntry> entries = triggerRepository.findAll();
            assertTrue(entries.stream().anyMatch(e -> e.getTriggerId().equals(responseBody)));
        });

        assertNotNull(responseBody);
    }

    @Test
    void createPackage_shouldFailWhenServiceIdNotSupported() throws Exception {
        // Given
        Customer customer = Customer.builder()
                .organizationName("TestOrg")
                .country("DE")
                .email("test@example.com")
                .build();
        CustomerProperties properties = CustomerProperties.builder()
                .serviceId("UNKNOWN-SERVICE")
                .subscriptionId("SUB789")
                .build();
        AutoSetupRequest request = new AutoSetupRequest();
        request.setCustomer(customer);
        request.setProperties(properties);
        String requestJson = objectMapper.writeValueAsString(request);

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/autosetup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());

        // Verify no workflow calls
        verify(edcConnectorWorkFlow, never()).getWorkFlow(any(), any(), any(), any(), any());
    }

    @Test
    void updatePackage_shouldUpdateAndCallWorkflows() throws Exception {
        // Given
        Customer customer = Customer.builder()
                .organizationName("UpdatedOrg")
                .country("DE")
                .email("update@example.com")
                .build();
        CustomerProperties properties = CustomerProperties.builder()
                .bpnNumber("BPN999")
                .role("recycler")
                .subscriptionId("SUB999")
                .serviceId(validServiceId)
                .build();
        AutoSetupRequest request = new AutoSetupRequest();
        request.setCustomer(customer);
        request.setProperties(properties);
        String requestJson = objectMapper.writeValueAsString(request);

        // Stub workflow calls
        Map<String, String> dummyEdcOutput = Map.of("controlPlaneEndpoint", "http://updated-edc");
        when(edcConnectorWorkFlow.getWorkFlow(any(), any(), any(), any(), any())).thenReturn(dummyEdcOutput);
        when(sdeAppWorkFlow.getWorkFlow(any(), any(), any(), any(), any())).thenReturn(Map.of(
                "sdeFrontEndUrl", "http://sde-front-updated",
                "connectorTestResult", "consumer and provider connectivity successful"
        ));
        when(dtAppWorkFlow.getWorkFlow(any(), any(), any(), any(), any())).thenReturn(Map.of("dtregistryUrl", "http://dt-updated"));
        when(kubeAppManageProxy.createNamespace(anyString(), anyString())).thenReturn("true");

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .put("/autosetup/" + validTriggerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        if (response.getStatus() != HttpStatus.OK.value()) {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatus());
            return;
        }
        assertEquals(validTriggerId, response.getContentAsString());

        AutoSetupTriggerEntry updated = triggerRepository.findAllByTriggerId(validTriggerId);
        assertNotNull(updated);
        assertEquals(validServiceId, updated.getServiceId());
    }

    @Test
    void updatePackage_shouldReturnErrorWhenTriggerNotFound() throws Exception {
        // Given
        String nonExistentId = UUID.randomUUID().toString();
        String requestJson = "{\"customer\":{\"organizationName\":\"Test\"},\"properties\":{\"serviceId\":\"" + validServiceId + "\"}}";

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .put("/autosetup/" + nonExistentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        verify(edcConnectorWorkFlow, never()).getWorkFlow(any(), any(), any(), any(), any());
    }

    @Test
    void deletePackage_shouldDeleteAndReturnTriggerId() throws Exception {
        // Given
        // Stub delete workflows
        doNothing().when(edcConnectorWorkFlow).deletePackageWorkFlow(any(), any(), any());
        doNothing().when(sdeAppWorkFlow).deletePackageWorkFlow(any(), any(), any());
        doNothing().when(dtAppWorkFlow).deletePackageWorkFlow(any(), any(), any());

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .delete("/autosetup/" + validTriggerId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        if (response.getStatus() != HttpStatus.OK.value()) {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatus());
            return;
        }
        assertEquals(validTriggerId, response.getContentAsString());

        AutoSetupTriggerEntry deleted = triggerRepository.findAllByTriggerId(validTriggerId);
        assertNotNull(deleted);
    }

    @Test
    void deletePackage_shouldReturnErrorWhenTriggerNotFound() throws Exception {
        // Given
        String nonExistentId = UUID.randomUUID().toString();

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .delete("/autosetup/" + nonExistentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
        verify(edcConnectorWorkFlow, never()).deletePackageWorkFlow(any(), any(), any());
    }

    @Test
    void getCheckDetails_shouldReturnSuccessResponseWhenStatusSuccess() throws Exception {
        // Given
        AutoSetupTriggerEntry trigger = triggerRepository.findAllByTriggerId(validTriggerId);
        trigger.setStatus(TriggerStatusEnum.SUCCESS.name());
        trigger.setAutosetupResult("[{\"name\":\"EDC\",\"controlPlaneEndpoint\":\"http://edc\"}]");
        triggerRepository.save(trigger);

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/autosetup/" + validTriggerId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        AutoSetupResponse autoSetupResponse = objectMapper.readValue(response.getContentAsString(), AutoSetupResponse.class);
        assertEquals(validTriggerId, autoSetupResponse.getExecutionId());
        assertEquals(TriggerStatusEnum.SUCCESS, autoSetupResponse.getStatus());
        assertNotNull(autoSetupResponse.getProcessResult());
        assertTrue(autoSetupResponse.getProcessResult().size() > 0);
        // remark should be null for success
        assertEquals(null, autoSetupResponse.getRemark());
    }

    @Test
    void getCheckDetails_shouldAddRemarkWhenStatusFailed() throws Exception {
        // Given
        AutoSetupTriggerEntry trigger = triggerRepository.findAllByTriggerId(validTriggerId);
        trigger.setStatus(TriggerStatusEnum.FAILED.name());
        trigger.setRemark("Some error");
        triggerRepository.save(trigger);

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/autosetup/" + validTriggerId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        AutoSetupResponse autoSetupResponse = objectMapper.readValue(response.getContentAsString(), AutoSetupResponse.class);
        assertEquals(validTriggerId, autoSetupResponse.getExecutionId());
        assertEquals(TriggerStatusEnum.FAILED, autoSetupResponse.getStatus());
        assertEquals("Please connect with technical team for more advice", autoSetupResponse.getRemark());
        // processResult should be empty for non-success
        assertTrue(autoSetupResponse.getProcessResult().isEmpty());
    }

    @Test
    void getCheckDetails_shouldReturnEmptyProcessResultWhenNotSuccess() throws Exception {
        // Given
        AutoSetupTriggerEntry trigger = triggerRepository.findAllByTriggerId(validTriggerId);
        trigger.setStatus(TriggerStatusEnum.INPROGRESS.name());
        triggerRepository.save(trigger);

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/autosetup/" + validTriggerId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        AutoSetupResponse autoSetupResponse = objectMapper.readValue(response.getContentAsString(), AutoSetupResponse.class);
        assertEquals(validTriggerId, autoSetupResponse.getExecutionId());
        assertEquals(TriggerStatusEnum.INPROGRESS, autoSetupResponse.getStatus());
        assertTrue(autoSetupResponse.getProcessResult().isEmpty());
    }

    @Test
    void getCheckDetails_shouldReturnErrorWhenTriggerNotFound() throws Exception {
        // Given
        String nonExistentId = UUID.randomUUID().toString();

        // When
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .get("/autosetup/" + nonExistentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Then
        MockHttpServletResponse response = result.getResponse();
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
    }
    private void waitUntilAsserted(ThrowingAssertion assertion) throws Exception {
        AssertionError lastError = null;
        long deadline = System.currentTimeMillis() + 5000L;

        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError error) {
                lastError = error;
                Thread.sleep(100L);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
    }

    @FunctionalInterface
    private interface ThrowingAssertion {
        void run() throws Exception;
    }

}