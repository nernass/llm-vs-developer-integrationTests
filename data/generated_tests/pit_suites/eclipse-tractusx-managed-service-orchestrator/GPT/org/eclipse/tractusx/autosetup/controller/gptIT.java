package org.eclipse.tractusx.autosetup.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
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
class gptIT {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AppRepository appRepository;

	private String validRequestBody;

	@BeforeEach
	void setUp() {
		appRepository.deleteAll();
		appRepository.flush();
		validRequestBody = buildRequestBody("EDC_DATAPLANE", "default", "kubeapps", "0.1.1", null);
	}

	@AfterEach
	void tearDown() {
		appRepository.deleteAll();
		appRepository.flush();
	}

	@Test
	void shouldCreateAppDetailsAndPersistToDatabase() throws Exception {
		mockMvc.perform(post("/internal/app-details")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(validRequestBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.appName", is("EDC_DATAPLANE")))
				.andExpect(jsonPath("$.contextCluster", is("default")))
				.andExpect(jsonPath("$.contextNamespace", is("kubeapps")))
				.andExpect(jsonPath("$.packageVersion", is("0.1.1")));

		AppDetails saved = appRepository.findByAppName("EDC_DATAPLANE").orElseThrow();
		assertEquals("default", saved.getContextCluster());
		assertEquals("kubeapps", saved.getContextNamespace());
		assertEquals("edcrepo/edc-dataplane", saved.getPackageIdentifier());
		assertNull(saved.getOutputData());
	}

	@Test
	void shouldUpdateExistingAppDetailsWhenAppNameAlreadyExists() throws Exception {
		appRepository.save(AppDetails.builder()
				.appName("EDC_DATAPLANE")
				.contextCluster("legacy-cluster")
				.contextNamespace("legacy-namespace")
				.packageIdentifier("legacy/package")
				.pluginName("helm.packages")
				.pluginVersion("v1alpha1")
				.packageVersion("0.0.1")
				.expectedInputData("legacy-input")
				.outputData("legacy-output")
				.requiredYamlConfiguration("legacy-yaml")
				.yamlValueFieldType("PROPERTY")
				.build());

		String updateRequestBody = buildRequestBody("EDC_DATAPLANE", "updated-cluster", "updated-namespace", "2.0.0",
				"updatedOutput");

		mockMvc.perform(post("/internal/app-details")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(updateRequestBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.appName", is("EDC_DATAPLANE")))
				.andExpect(jsonPath("$.contextCluster", is("updated-cluster")))
				.andExpect(jsonPath("$.contextNamespace", is("updated-namespace")))
				.andExpect(jsonPath("$.packageVersion", is("2.0.0")))
				.andExpect(jsonPath("$.outputData", is("updatedOutput")));

		AppDetails updated = appRepository.findByAppName("EDC_DATAPLANE").orElseThrow();
		assertEquals(1, appRepository.count());
		assertEquals("updated-cluster", updated.getContextCluster());
		assertEquals("updated-namespace", updated.getContextNamespace());
		assertEquals("2.0.0", updated.getPackageVersion());
		assertEquals("updatedOutput", updated.getOutputData());
	}

	@Test
	void shouldReturnBadRequestWhenAppNameIsBlank() throws Exception {
		String invalidRequestBody = buildRequestBody("", "default", "kubeapps", "0.1.1", null);

		mockMvc.perform(post("/internal/app-details")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(invalidRequestBody))
				.andExpect(status().isBadRequest());

		assertEquals(0, appRepository.count());
	}

	@Test
	void shouldReturnBadRequestWhenAppNameContainsInvalidCharacters() throws Exception {
		String invalidRequestBody = buildRequestBody("EDC@DATAPLANE", "default", "kubeapps", "0.1.1", null);

		mockMvc.perform(post("/internal/app-details")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(invalidRequestBody))
				.andExpect(status().isBadRequest());

		assertEquals(0, appRepository.count());
	}

	@Test
	void shouldReturnBadRequestWhenRequiredFieldsAreMissing() throws Exception {
		String invalidRequestBody = """
				{
				  "appName": "EDC_DATAPLANE"
				}
				""";

		mockMvc.perform(post("/internal/app-details")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(invalidRequestBody))
				.andExpect(status().isBadRequest());

		assertEquals(0, appRepository.count());
	}

	@Test
	void shouldReturnAppDetailsByAppName() throws Exception {
		appRepository.save(AppDetails.builder()
				.appName("EDC_CONTROLPLANE")
				.contextCluster("default")
				.contextNamespace("kubeapps")
				.packageIdentifier("edcrepo/edc-controlplane")
				.pluginName("helm.packages")
				.pluginVersion("v1alpha1")
				.packageVersion("1.0.0")
				.expectedInputData("edc.hostname=${dnsName}")
				.outputData("controlplane")
				.requiredYamlConfiguration("configuration=${yamlValues}")
				.yamlValueFieldType("PROPERTY")
				.build());

		mockMvc.perform(get("/internal/app-details/EDC_CONTROLPLANE")
				.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.appName", is("EDC_CONTROLPLANE")))
				.andExpect(jsonPath("$.contextCluster", is("default")))
				.andExpect(jsonPath("$.packageIdentifier", is("edcrepo/edc-controlplane")))
				.andExpect(jsonPath("$.outputData", is("controlplane")));
	}

	@Test
	void shouldReturnNotFoundWhenAppDetailsDoNotExist() throws Exception {
		mockMvc.perform(get("/internal/app-details/UNKNOWN_APP")
				.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotFound())
				.andExpect(content().string("No data found for UNKNOWN_APP"));
	}

	@Test
	void shouldReturnAllAppDetails() throws Exception {
		appRepository.save(AppDetails.builder()
				.appName("APP_ONE")
				.contextCluster("cluster-one")
				.contextNamespace("namespace-one")
				.packageIdentifier("repo/app-one")
				.pluginName("helm.packages")
				.pluginVersion("v1alpha1")
				.packageVersion("1.0.0")
				.expectedInputData("input-one")
				.outputData(null)
				.requiredYamlConfiguration("yaml-one")
				.yamlValueFieldType("PROPERTY")
				.build());

		appRepository.save(AppDetails.builder()
				.appName("APP_TWO")
				.contextCluster("cluster-two")
				.contextNamespace("namespace-two")
				.packageIdentifier("repo/app-two")
				.pluginName("helm.packages")
				.pluginVersion("v1alpha1")
				.packageVersion("2.0.0")
				.expectedInputData("input-two")
				.outputData("output-two")
				.requiredYamlConfiguration("yaml-two")
				.yamlValueFieldType("PROPERTY")
				.build());

		mockMvc.perform(get("/internal/app-details")
				.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].appName", containsInAnyOrder("APP_ONE", "APP_TWO")));
	}

	@Test
	void shouldReturnEmptyArrayWhenNoAppDetailsExist() throws Exception {
		mockMvc.perform(get("/internal/app-details")
				.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	private String buildRequestBody(String appName, String contextCluster, String contextNamespace,
			String packageVersion, String outputData) {
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
				""".formatted(appName, contextCluster, contextNamespace, packageVersion, asJsonValue(outputData));
	}

	private String asJsonValue(String value) {
		if (value == null) {
			return "null";
		}
		return "\"" + value + "\"";
	}
}