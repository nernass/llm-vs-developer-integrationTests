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

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.eclipse.tractusx.autosetup.entity.AppDetails;
import org.eclipse.tractusx.autosetup.repository.AppRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ActiveProfiles("test")
@Transactional
class AppDetailsControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppRepository appRepository;

    @Test
    void shouldCreateAppDetailsAndPersistToDatabase() throws Exception {
        String appName = "APP_CREATE_SAMPLE_X1";
        long initialCount = appRepository.count();

        mockMvc.perform(post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(buildRequestBody(appName, "default", "kubeapps", "1.2.3", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName", is(appName)))
                .andExpect(jsonPath("$.contextCluster", is("default")))
                .andExpect(jsonPath("$.contextNamespace", is("kubeapps")))
                .andExpect(jsonPath("$.packageVersion", is("1.2.3")));

        assertEquals(initialCount + 1, appRepository.count());

        AppDetails saved = appRepository.findByAppName(appName).orElseThrow();
        assertEquals("default", saved.getContextCluster());
        assertEquals("kubeapps", saved.getContextNamespace());
        assertEquals("edcrepo/edc-dataplane", saved.getPackageIdentifier());
        assertNull(saved.getOutputData());
    }

    @Test
    void shouldUpdateExistingAppDetailsWhenAppNameAlreadyExists() throws Exception {
        String appName = "APP_UPDATE_SAMPLE_X1";

        appRepository.saveAndFlush(AppDetails.builder()
                .appName(appName)
                .contextCluster("legacy-cluster")
                .contextNamespace("legacy-namespace")
                .packageIdentifier("legacy/package")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("0.0.1")
                .expectedInputData("legacy-input")
                .outputData("legacyOutput")
                .requiredYamlConfiguration("legacyConfig")
                .yamlValueFieldType("PROPERTY")
                .build());

        long countAfterSeed = appRepository.count();

        mockMvc.perform(post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(buildRequestBody(appName, "updated-cluster", "updated-namespace", "2.0.0", "updatedOutput")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName", is(appName)))
                .andExpect(jsonPath("$.contextCluster", is("updated-cluster")))
                .andExpect(jsonPath("$.contextNamespace", is("updated-namespace")))
                .andExpect(jsonPath("$.packageVersion", is("2.0.0")))
                .andExpect(jsonPath("$.outputData", is("updatedOutput")));

        assertEquals(countAfterSeed, appRepository.count());

        AppDetails updated = appRepository.findByAppName(appName).orElseThrow();
        assertEquals("updated-cluster", updated.getContextCluster());
        assertEquals("updated-namespace", updated.getContextNamespace());
        assertEquals("2.0.0", updated.getPackageVersion());
        assertEquals("updatedOutput", updated.getOutputData());
    }

    @Test
    void shouldReturnBadRequestWhenAppNameIsBlank() throws Exception {
        long initialCount = appRepository.count();

        mockMvc.perform(post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(buildRequestBody("", "default", "kubeapps", "1.2.3", null)))
                .andExpect(status().isBadRequest());

        assertEquals(initialCount, appRepository.count());
    }

    @Test
    void shouldReturnBadRequestWhenAppNameContainsInvalidCharacters() throws Exception {
        long initialCount = appRepository.count();

        mockMvc.perform(post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(buildRequestBody("APP@INVALID!", "default", "kubeapps", "1.2.3", null)))
                .andExpect(status().isBadRequest());

        assertEquals(initialCount, appRepository.count());
    }

    @Test
    void shouldReturnBadRequestWhenRequiredFieldsAreMissing() throws Exception {
        long initialCount = appRepository.count();

        mockMvc.perform(post("/internal/app-details")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "appName": "APP_MISSING_FIELDS_X1"
                        }
                        """))
                .andExpect(status().isBadRequest());

        assertEquals(initialCount, appRepository.count());
    }

    @Test
    void shouldReturnAppDetailsByAppName() throws Exception {
        String appName = "APP_FETCH_SAMPLE_X1";

        appRepository.saveAndFlush(AppDetails.builder()
                .appName(appName)
                .contextCluster("fetch-cluster")
                .contextNamespace("fetch-namespace")
                .packageIdentifier("repo/app-fetch")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("3.1.4")
                .expectedInputData("fetchInput")
                .outputData("fetchOutput")
                .requiredYamlConfiguration("fetchConfig")
                .yamlValueFieldType("PROPERTY")
                .build());

        mockMvc.perform(get("/internal/app-details/" + appName)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName", is(appName)))
                .andExpect(jsonPath("$.contextCluster", is("fetch-cluster")))
                .andExpect(jsonPath("$.contextNamespace", is("fetch-namespace")))
                .andExpect(jsonPath("$.packageVersion", is("3.1.4")));
    }

    @Test
    void shouldReturn404WhenAppDetailsDoNotExist() throws Exception {
        String missingAppName = "APP_DOES_NOT_EXIST_X1";

        mockMvc.perform(get("/internal/app-details/" + missingAppName)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No data found for " + missingAppName));
    }

    @Test
    void shouldReturnAllAppDetailsIncludingNewlyPersistedRecords() throws Exception {
        String firstAppName = "APP_LIST_SAMPLE_X1";
        String secondAppName = "APP_LIST_SAMPLE_X2";

        appRepository.saveAndFlush(AppDetails.builder()
                .appName(firstAppName)
                .contextCluster("cluster-one")
                .contextNamespace("namespace-one")
                .packageIdentifier("repo/app-one")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("1.0.0")
                .expectedInputData("inputOne")
                .outputData(null)
                .requiredYamlConfiguration("configOne")
                .yamlValueFieldType("PROPERTY")
                .build());

        appRepository.saveAndFlush(AppDetails.builder()
                .appName(secondAppName)
                .contextCluster("cluster-two")
                .contextNamespace("namespace-two")
                .packageIdentifier("repo/app-two")
                .pluginName("helm.packages")
                .pluginVersion("v1alpha1")
                .packageVersion("2.0.0")
                .expectedInputData("inputTwo")
                .outputData("outputTwo")
                .requiredYamlConfiguration("configTwo")
                .yamlValueFieldType("PROPERTY")
                .build());

        mockMvc.perform(get("/internal/app-details")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].appName", hasItems(firstAppName, secondAppName)));
    }

    private String buildRequestBody(
            String appName,
            String contextCluster,
            String contextNamespace,
            String packageVersion,
            String outputData) {

        return """
                {
                  "appName": "%s",
                  "contextCluster": "%s",
                  "contextNamespace": "%s",
                  "packageIdentifier": "edcrepo/edc-dataplane",
                  "pluginName": "helm.packages",
                  "pluginVersion": "v1alpha1",
                  "packageVersion": "%s",
                  "expectedInputData": "edc.hostname=${dnsName}",
                  "outputData": %s,
                  "requiredYamlConfiguration": "configuration=${yamlValues}",
                  "yamlValueFieldType": "PROPERTY"
                }
                """.formatted(
                appName,
                contextCluster,
                contextNamespace,
                packageVersion,
                asJsonValue(outputData));
    }

    private String asJsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}