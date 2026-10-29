package org.eclipse.tractusx.autosetup.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.eclipse.tractusx.autosetup.entity.AppDetails;
import org.eclipse.tractusx.autosetup.repository.AppRepository;
import org.eclipse.tractusx.autosetup.repository.AppServiceCatalogAndCustomerMappingRepository;
import org.eclipse.tractusx.autosetup.repository.AppServiceCatalogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@Transactional
class GeminiIT_1 {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private AppServiceCatalogRepository appServiceCatalogRepository;

    @Autowired
    private AppServiceCatalogAndCustomerMappingRepository appServiceCatalogAndCustomerMappingRepository;

    @BeforeEach
    void setUp() {
        appServiceCatalogAndCustomerMappingRepository.deleteAll();
        appServiceCatalogAndCustomerMappingRepository.flush();
        appServiceCatalogRepository.deleteAll();
        appServiceCatalogRepository.flush();
        appRepository.deleteAll();
        appRepository.flush();
    }

    @AfterEach
    void tearDown() {
        appServiceCatalogAndCustomerMappingRepository.deleteAll();
        appServiceCatalogAndCustomerMappingRepository.flush();
        appServiceCatalogRepository.deleteAll();
        appServiceCatalogRepository.flush();
        appRepository.deleteAll();
        appRepository.flush();
    }

    @Test
    void shouldCreateAppDetailsSuccessfully() throws Exception {
        String requestPayload = """
                {
                  "appName": "test-app",
                  "contextCluster": "test-cluster",
                  "contextNamespace": "test-namespace",
                  "packageIdentifier": "test-package",
                  "pluginName": "test-plugin",
                  "pluginVersion": "v1",
                  "packageVersion": "1.0",
                  "expectedInputData": "key=value",
                  "outputData": "output",
                  "requiredYamlConfiguration": "yaml-config",
                  "yamlValueFieldType": "PROPERTY"
                }
                """;

        mockMvc.perform(post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("test-app"))
                .andExpect(jsonPath("$.contextCluster").value("test-cluster"));
    }

    @Test
    void shouldReturn400ForInvalidAppDetailsRequest() throws Exception {
        String invalidPayload = """
                {
                  "appName": ""
                }
                """;
        mockMvc.perform(post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnAppDetailsByAppName() throws Exception {
        AppDetails appDetails = AppDetails.builder()
                .appName("existing-app")
                .contextCluster("cluster")
                .contextNamespace("namespace")
                .packageIdentifier("package")
                .pluginName("plugin")
                .pluginVersion("1.0")
                .packageVersion("1.0")
                .expectedInputData("input")
                .requiredYamlConfiguration("config")
                .yamlValueFieldType("PROPERTY")
                .build();
        appRepository.save(appDetails);

        mockMvc.perform(get("/internal/app-details/existing-app")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("existing-app"));
    }

    @Test
    void shouldReturn404WhenAppDetailsNotFound() throws Exception {
        mockMvc.perform(get("/internal/app-details/non-existent-app")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnAllAppDetails() throws Exception {
        AppDetails appDetails1 = AppDetails.builder()
                .appName("app1")
                .contextCluster("c")
                .contextNamespace("n")
                .packageIdentifier("p")
                .pluginName("pl")
                .pluginVersion("pv")
                .packageVersion("pv2")
                .expectedInputData("eid")
                .requiredYamlConfiguration("ryc")
                .yamlValueFieldType("yv")
                .build();

        AppDetails appDetails2 = AppDetails.builder()
                .appName("app2")
                .contextCluster("c")
                .contextNamespace("n")
                .packageIdentifier("p")
                .pluginName("pl")
                .pluginVersion("pv")
                .packageVersion("pv2")
                .expectedInputData("eid")
                .requiredYamlConfiguration("ryc")
                .yamlValueFieldType("yv")
                .build();

        appRepository.save(appDetails1);
        appRepository.save(appDetails2);

        mockMvc.perform(get("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void shouldCreateCatalogService() throws Exception {
        String requestPayload = """
                {
                  "canonicalServiceId": "service-1",
                  "name": "test-service",
                  "workflow": "workflow-1",
                  "serviceTools": "tools"
                }
                """;

        mockMvc.perform(post("/internal/catalog-service")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalServiceId").value("service-1"));
    }
}