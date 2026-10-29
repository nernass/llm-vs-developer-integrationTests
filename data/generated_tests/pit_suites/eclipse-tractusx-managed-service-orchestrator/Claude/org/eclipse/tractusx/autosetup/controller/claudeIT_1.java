package org.eclipse.tractusx.autosetup.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.eclipse.tractusx.autosetup.entity.AppDetails;
import org.eclipse.tractusx.autosetup.entity.AppServiceCatalog;
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

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
class claudeIT_1 {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private AppServiceCatalogRepository appServiceCatalogRepository;

    @Autowired
    private AppServiceCatalogAndCustomerMappingRepository appServiceCatalogMappingRepository;

    // ======================== POST /internal/app-details ========================

    @Test
    void shouldCreateAppDetailsAndReturn200() throws Exception {
        String request = """
                {
                  "appName": "EDC_DATAPLANE",
                  "contextCluster": "default",
                  "contextNamespace": "kubeapps",
                  "packageIdentifier": "edcrepo/edc-dataplane",
                  "pluginName": "helm.packages",
                  "pluginVersion": "v1alpha1",
                  "packageVersion": "0.1.1",
                  "expectedInputData": "edc.hostname=${dnsName}",
                  "outputData": null,
                  "requiredYamlConfiguration": "configuration: ${yamlValues}",
                  "yamlValueFieldType": "PROPERTY"
                }
                """;

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName", is("EDC_DATAPLANE")))
                .andExpect(jsonPath("$.contextCluster", is("default")))
                .andExpect(jsonPath("$.packageVersion", is("0.1.1")));

        assertEquals(4, appRepository.count());
    }

    @Test
    void shouldUpdateExistingAppDetailsWhenSameAppNamePosted() throws Exception {
        AppDetails existing = AppDetails.builder()
                .appName("EDC_CONTROLPLANE")
                .contextCluster("old-cluster")
                .contextNamespace("old-ns")
                .packageIdentifier("old/package")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("0.0.1")
                .expectedInputData("old-data")
                .requiredYamlConfiguration("old-yaml")
                .yamlValueFieldType("PROPERTY")
                .build();
        appRepository.save(existing);

        String updateRequest = """
                {
                  "appName": "EDC_CONTROLPLANE",
                  "contextCluster": "new-cluster",
                  "contextNamespace": "new-ns",
                  "packageIdentifier": "newrepo/edc-controlplane",
                  "pluginName": "helm.packages",
                  "pluginVersion": "v1alpha1",
                  "packageVersion": "1.0.0",
                  "expectedInputData": "new-input-data",
                  "requiredYamlConfiguration": "new-yaml-config",
                  "yamlValueFieldType": "PROPERTY"
                }
                """;

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName", is("EDC_CONTROLPLANE")))
                .andExpect(jsonPath("$.contextCluster", is("new-cluster")))
                .andExpect(jsonPath("$.packageVersion", is("1.0.0")));

        assertEquals(4, appRepository.count());
    }

    @Test
    void shouldReturn400WhenAppNameIsBlank() throws Exception {
        String request = """
                {
                  "appName": "",
                  "contextCluster": "default",
                  "contextNamespace": "kubeapps",
                  "packageIdentifier": "edcrepo/edc-dataplane",
                  "pluginName": "helm.packages",
                  "pluginVersion": "v1alpha1",
                  "packageVersion": "0.1.1",
                  "expectedInputData": "edc.hostname=${dnsName}",
                  "requiredYamlConfiguration": "configuration: ${yamlValues}",
                  "yamlValueFieldType": "PROPERTY"
                }
                """;

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400WhenRequiredFieldsAreMissing() throws Exception {
        String request = """
                {
                  "appName": "EDC_DATAPLANE"
                }
                """;

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400WhenAppNameContainsSpecialCharacters() throws Exception {
        String request = """
                {
                  "appName": "EDC@DATAPLANE!",
                  "contextCluster": "default",
                  "contextNamespace": "kubeapps",
                  "packageIdentifier": "edcrepo/edc-dataplane",
                  "pluginName": "helm.packages",
                  "pluginVersion": "v1alpha1",
                  "packageVersion": "0.1.1",
                  "expectedInputData": "edc.hostname=${dnsName}",
                  "requiredYamlConfiguration": "configuration: ${yamlValues}",
                  "yamlValueFieldType": "PROPERTY"
                }
                """;

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    // ======================== GET /internal/app-details/{appName} ========================

    @Test
    void shouldReturnAppDetailsByName() throws Exception {
        AppDetails app = AppDetails.builder()
                .appName("EDC_DATAPLANE")
                .contextCluster("default")
                .contextNamespace("kubeapps")
                .packageIdentifier("edcrepo/edc-dataplane")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("0.1.1")
                .expectedInputData("edc.hostname=${dnsName}")
                .requiredYamlConfiguration("configuration: ${yamlValues}")
                .yamlValueFieldType("PROPERTY")
                .build();
        appRepository.save(app);

        mockMvc.perform(get("/internal/app-details/EDC_DATAPLANE")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName", is("EDC_DATAPLANE")))
                .andExpect(jsonPath("$.contextCluster", is("default")))
                .andExpect(jsonPath("$.packageIdentifier", is("edcrepo/edc-dataplane")));
    }

    @Test
    void shouldReturn404WhenAppNameNotFound() throws Exception {
        mockMvc.perform(get("/internal/app-details/NON_EXISTENT_APP")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ======================== GET /internal/app-details ========================

    @Test
    void shouldReturnAllAppDetails() throws Exception {
        appRepository.save(AppDetails.builder()
                .appName("APP_ONE")
                .contextCluster("cluster1")
                .contextNamespace("ns1")
                .packageIdentifier("repo/app-one")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("1.0.0")
                .expectedInputData("input1")
                .requiredYamlConfiguration("yaml1")
                .yamlValueFieldType("PROPERTY")
                .build());

        appRepository.save(AppDetails.builder()
                .appName("APP_TWO")
                .contextCluster("cluster2")
                .contextNamespace("ns2")
                .packageIdentifier("repo/app-two")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("2.0.0")
                .expectedInputData("input2")
                .requiredYamlConfiguration("yaml2")
                .yamlValueFieldType("PROPERTY")
                .build());

        mockMvc.perform(get("/internal/app-details")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    void shouldReturnEmptyListWhenNoAppDetailsExist() throws Exception {
        appRepository.deleteAll();

        mockMvc.perform(get("/internal/app-details")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ======================== POST /internal/catalog-service ========================

    @Test
    void shouldCreateCatalogServiceAndReturn200() throws Exception {
        String request = """
                {
                  "canonicalServiceId": "svc-001",
                  "name": "TestService",
                  "workflow": "EDC_SETUP",
                  "serviceTools": "tool1, tool2"
                }
                """;

        mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId", is("svc-001")))
                .andExpect(jsonPath("$.name", is("TestService")))
                .andExpect(jsonPath("$.workflow", is("EDC_SETUP")));

        assertEquals(4, appServiceCatalogRepository.count());
    }

    @Test
    void shouldReturn400WhenCatalogServiceNameIsBlank() throws Exception {
        String request = """
                {
                  "canonicalServiceId": "svc-001",
                  "name": "",
                  "workflow": "EDC_SETUP",
                  "serviceTools": "tool1"
                }
                """;

        mockMvc.perform(post("/internal/catalog-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    // ======================== GET /internal/catalog-service/{id} ========================

    @Test
    void shouldReturnCatalogServiceById() throws Exception {
        AppServiceCatalog catalog = AppServiceCatalog.builder()
                .canonicalServiceId("svc-001")
                .name("TestService")
                .workflow("EDC_SETUP")
                .serviceTools("tool1, tool2")
                .build();
        appServiceCatalogRepository.save(catalog);

        mockMvc.perform(get("/internal/catalog-service/svc-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId", is("svc-001")))
                .andExpect(jsonPath("$.name", is("TestService")));
    }

    @Test
    void shouldReturn404WhenCatalogServiceNotFound() throws Exception {
        mockMvc.perform(get("/internal/catalog-service/NON_EXISTENT")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ======================== GET /internal/catalog-service ========================

    @Test
    void shouldReturnAllCatalogServices() throws Exception {
        appServiceCatalogRepository.save(AppServiceCatalog.builder()
                .canonicalServiceId("svc-001")
                .name("ServiceOne")
                .workflow("WORKFLOW_A")
                .serviceTools("tool1")
                .build());

        appServiceCatalogRepository.save(AppServiceCatalog.builder()
                .canonicalServiceId("svc-002")
                .name("ServiceTwo")
                .workflow("WORKFLOW_B")
                .serviceTools("tool2")
                .build());

        mockMvc.perform(get("/internal/catalog-service")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    // ======================== POST /internal/catalog-service-mapping ========================

    @Test
    void shouldCreateCatalogServiceMappingAndReturn200() throws Exception {
        AppServiceCatalog catalog = AppServiceCatalog.builder()
                .canonicalServiceId("svc-001")
                .name("TestService")
                .workflow("EDC_SETUP")
                .serviceTools("tool1")
                .build();
        appServiceCatalogRepository.save(catalog);

        String request = """
                {
                  "customer": "CustomerA",
                  "serviceId": "service-123",
                  "canonicalId": "svc-001"
                }
                """;

        mockMvc.perform(post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer", is("CustomerA")))
                .andExpect(jsonPath("$.serviceId", is("service-123")));
    }

    @Test
    void shouldReturn404WhenMappingReferencesNonExistentCatalog() throws Exception {
        String request = """
                {
                  "customer": "CustomerA",
                  "serviceId": "service-123",
                  "canonicalId": "NON_EXISTENT"
                }
                """;

        mockMvc.perform(post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn400WhenCatalogMappingCustomerIsBlank() throws Exception {
        String request = """
                {
                  "customer": "",
                  "serviceId": "service-123",
                  "canonicalId": "svc-001"
                }
                """;

        mockMvc.perform(post("/internal/catalog-service-mapping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    // ======================== GET /internal/catalog-service-mapping/{id} ========================

    @Test
    void shouldReturnCatalogServiceMappingByServiceId() throws Exception {
        AppServiceCatalog catalog = AppServiceCatalog.builder()
                .canonicalServiceId("svc-001")
                .name("TestService")
                .workflow("EDC_SETUP")
                .serviceTools("tool1")
                .build();
        appServiceCatalogRepository.save(catalog);

        appServiceCatalogMappingRepository.save(
                org.eclipse.tractusx.autosetup.entity.AppServiceCatalogAndCustomerMapping.builder()
                        .customer("CustomerA")
                        .serviceId("service-123")
                        .serviceCatalog(catalog)
                        .build());

        mockMvc.perform(get("/internal/catalog-service-mapping/service-123")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer", is("CustomerA")))
                .andExpect(jsonPath("$.serviceId", is("service-123")));
    }

    // ======================== GET /internal/catalog-service-mapping ========================

    @Test
    void shouldReturnAllCatalogServiceMappings() throws Exception {
        AppServiceCatalog catalog = AppServiceCatalog.builder()
                .canonicalServiceId("svc-001")
                .name("TestService")
                .workflow("EDC_SETUP")
                .serviceTools("tool1")
                .build();
        appServiceCatalogRepository.save(catalog);

        appServiceCatalogMappingRepository.save(
                org.eclipse.tractusx.autosetup.entity.AppServiceCatalogAndCustomerMapping.builder()
                        .customer("CustomerA")
                        .serviceId("service-AAA")
                        .serviceCatalog(catalog)
                        .build());

        appServiceCatalogMappingRepository.save(
                org.eclipse.tractusx.autosetup.entity.AppServiceCatalogAndCustomerMapping.builder()
                        .customer("CustomerB")
                        .serviceId("service-BBB")
                        .serviceCatalog(catalog)
                        .build());

        mockMvc.perform(get("/internal/catalog-service-mapping")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void shouldPersistAppDetailsWithNullOutputData() throws Exception {
        String request = """
                {
                  "appName": "EDC_DATAPLANE",
                  "contextCluster": "default",
                  "contextNamespace": "kubeapps",
                  "packageIdentifier": "edcrepo/edc-dataplane",
                  "pluginName": "helm.packages",
                  "pluginVersion": "v1alpha1",
                  "packageVersion": "0.1.1",
                  "expectedInputData": "edc.hostname=${dnsName}",
                  "requiredYamlConfiguration": "configuration: ${yamlValues}",
                  "yamlValueFieldType": "PROPERTY"
                }
                """;

        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputData", is(nullValue())));

        AppDetails saved = appRepository.findByAppName("EDC_DATAPLANE").orElseThrow();
        assertEquals(null, saved.getOutputData());
    }

    @Test
    void shouldReturn400WhenRequestBodyIsEmpty() throws Exception {
        mockMvc.perform(post("/internal/app-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
