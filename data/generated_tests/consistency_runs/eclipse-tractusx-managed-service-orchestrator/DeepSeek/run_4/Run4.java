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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
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

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private AppServiceCatalogRepository catalogRepository;

    @Autowired
    private AppServiceCatalogAndCustomerMappingRepository mappingRepository;

    // -------------------- AppDetails endpoints --------------------

    @Test
    void createOrUpdateAppInfo_shouldReturnCreatedAppDetails() throws Exception {
        AppDetailsRequest request = AppDetailsRequest.builder()
                .appName("EDC_DATAPLANE")
                .contextCluster("default")
                .contextNamespace("kubeapps")
                .packageIdentifier("edcrepo/edc-dataplane")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("0.1.1")
                .expectedInputData("edc.hostname=${dnsName}\n\tedc.vault.hashicorp.url=${vaulturl}\n\tedc.vault.hashicorp.token=${vaulttoken}\n\tedc.vault.hashicorp.timeout.seconds=${vaulttimeout}\n\tedc.dataplane.token.validation.endpoint=${controlPlaneValidationEndpoint}")
                .outputData(null)
                .requiredYamlConfiguration("{\"ingresses\":[{\"enabled\": true, \"hostname\": \"${dnsName}\", \"annotations\": {}, \"className\": \"nginx\", \"endpoints\":[\"public\"], \"tls\":{\"enabled\": true, \"secretName\":\"edcdataplane\"},\"certManager\":{\"clusterIssuer\":\"letsencrypt-staging\"}}], \"configuration\": {\"properties\": \"${yamlValues}\"}}")
                .yamlValueFieldType("PROPERTY")
                .build();

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("EDC_DATAPLANE"))
                .andExpect(jsonPath("$.packageIdentifier").value("edcrepo/edc-dataplane"));
    }

    @Test
    void createOrUpdateAppInfo_withMissingRequiredField_shouldReturnBadRequest() throws Exception {
        // missing appName
        AppDetailsRequest request = AppDetailsRequest.builder()
                .contextCluster("default")
                .contextNamespace("kubeapps")
                .packageIdentifier("edcrepo/edc-dataplane")
                .build();

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAppInfo_whenExists_shouldReturnAppDetails() throws Exception {
        // given
        AppDetails existing = AppDetails.builder()
                .appName("TestApp")
                .contextCluster("default")
                .contextNamespace("test")
                .packageIdentifier("test/test")
                .pluginName("helm")
                .pluginVersion("v1")
                .packageVersion("1.0")
                .expectedInputData("key=value")
                .requiredYamlConfiguration("{}")
                .yamlValueFieldType("PROPERTY")
                .build();
        appRepository.save(existing);

        // when / then
        mockMvc.perform(get("/internal/app-details/TestApp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("TestApp"))
                .andExpect(jsonPath("$.packageIdentifier").value("test/test"));
    }

    @Test
    void getAppInfo_whenNotExists_shouldReturnNotFound() throws Exception {
        mockMvc.perform(get("/internal/app-details/NonExistentApp"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAllAppInfo_shouldReturnList() throws Exception {
        // given
        AppDetails app1 = AppDetails.builder()
                .appName("App1")
                .contextCluster("default")
                .contextNamespace("ns1")
                .packageIdentifier("pkg1")
                .pluginName("helm")
                .pluginVersion("v1")
                .packageVersion("1.0")
                .expectedInputData("")
                .requiredYamlConfiguration("{}")
                .yamlValueFieldType("PROPERTY")
                .build();
        AppDetails app2 = AppDetails.builder()
                .appName("App2")
                .contextCluster("default")
                .contextNamespace("ns2")
                .packageIdentifier("pkg2")
                .pluginName("helm")
                .pluginVersion("v1")
                .packageVersion("2.0")
                .expectedInputData("")
                .requiredYamlConfiguration("{}")
                .yamlValueFieldType("PROPERTY")
                .build();
        appRepository.saveAll(List.of(app1, app2));

        mockMvc.perform(get("/internal/app-details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].appName").value("App1"))
                .andExpect(jsonPath("$[1].appName").value("App2"));
    }

    // -------------------- AppServiceCatalog endpoints --------------------

    @Test
    void createCatalogService_shouldReturnSavedCatalog() throws Exception {
        AppServiceCatalogPojo pojo = AppServiceCatalogPojo.builder()
                .canonicalServiceId("service-123")
                .name("Test Service")
                .description("Test Description")
                .version("1.0")
                .build();

        mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pojo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId").value("service-123"))
                .andExpect(jsonPath("$.name").value("Test Service"));
    }

    @Test
    void getCatalogService_whenExists_shouldReturnCatalog() throws Exception {
        // given
        AppServiceCatalog catalog = AppServiceCatalog.builder()
                .canonicalServiceId("cat-456")
                .name("Existing Catalog")
                .description("Desc")
                .version("2.0")
                .build();
        catalogRepository.save(catalog);

        mockMvc.perform(get("/internal/catalog-service/cat-456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId").value("cat-456"))
                .andExpect(jsonPath("$.name").value("Existing Catalog"));
    }

    @Test
    void getCatalogService_whenNotExists_shouldReturnNotFound() throws Exception {
        mockMvc.perform(get("/internal/catalog-service/unknown-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAllCatalogService_shouldReturnList() throws Exception {
        // given
        AppServiceCatalog cat1 = AppServiceCatalog.builder()
                .canonicalServiceId("c1")
                .name("Cat1")
                .description("desc1")
                .version("1")
                .build();
        AppServiceCatalog cat2 = AppServiceCatalog.builder()
                .canonicalServiceId("c2")
                .name("Cat2")
                .description("desc2")
                .version("2")
                .build();
        catalogRepository.saveAll(List.of(cat1, cat2));

        mockMvc.perform(get("/internal/catalog-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].canonicalServiceId").value("c1"))
                .andExpect(jsonPath("$[1].canonicalServiceId").value("c2"));
    }

    // -------------------- AppServiceCatalogAndCustomerMapping endpoints --------------------

    @Test
    void createCatalogServiceMapping_shouldReturnSavedMapping() throws Exception {
        // given: a catalog service must exist
        AppServiceCatalog catalog = AppServiceCatalog.builder()
                .canonicalServiceId("cat-mapping")
                .name("Catalog for mapping")
                .description("desc")
                .version("1")
                .build();
        catalogRepository.save(catalog);

        AppServiceCatalogAndCustomerMappingPojo pojo = AppServiceCatalogAndCustomerMappingPojo.builder()
                .serviceId("mapping-123")
                .canonicalId("cat-mapping")
                .customerId("cust-456")
                .build();

        mockMvc.perform(post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pojo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("mapping-123"))
                .andExpect(jsonPath("$.customerId").value("cust-456"));
    }

    @Test
    void getCatalogServiceMapping_whenExists_shouldReturnMapping() throws Exception {
        // given
        AppServiceCatalog catalog = AppServiceCatalog.builder()
                .canonicalServiceId("cat-mapping-get")
                .name("Catalog get")
                .description("desc")
                .version("1")
                .build();
        catalogRepository.save(catalog);

        AppServiceCatalogAndCustomerMapping mapping = AppServiceCatalogAndCustomerMapping.builder()
                .serviceId("mapping-get")
                .customerId("cust-get")
                .canonicalId("cat-mapping-get")
                .serviceCatalog(catalog)
                .build();
        mappingRepository.save(mapping);

        mockMvc.perform(get("/internal/catalog-service-mapping/mapping-get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("mapping-get"))
                .andExpect(jsonPath("$.customerId").value("cust-get"));
    }

    @Test
    void getCatalogServiceMapping_whenNotExists_shouldReturnNull() throws Exception {
        // The service returns null, controller returns null with status 200
        mockMvc.perform(get("/internal/catalog-service-mapping/non-existent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    void getAllCatalogServiceMapping_shouldReturnList() throws Exception {
        // given
        AppServiceCatalog catalog = AppServiceCatalog.builder()
                .canonicalServiceId("cat-all")
                .name("Catalog all")
                .description("desc")
                .version("1")
                .build();
        catalogRepository.save(catalog);

        AppServiceCatalogAndCustomerMapping m1 = AppServiceCatalogAndCustomerMapping.builder()
                .serviceId("m1")
                .customerId("c1")
                .canonicalId("cat-all")
                .serviceCatalog(catalog)
                .build();
        AppServiceCatalogAndCustomerMapping m2 = AppServiceCatalogAndCustomerMapping.builder()
                .serviceId("m2")
                .customerId("c2")
                .canonicalId("cat-all")
                .serviceCatalog(catalog)
                .build();
        mappingRepository.saveAll(List.of(m1, m2));

        mockMvc.perform(get("/internal/catalog-service-mapping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].serviceId").value("m1"))
                .andExpect(jsonPath("$[1].serviceId").value("m2"));
    }
}