package com.example.productserviceshop.productTest;

import com.example.productserviceshop.entity.Product;
import com.example.productserviceshop.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.mvc.pathmatch.matching-strategy=ant_path_matcher"
        }
)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional
public class gptIT_2 {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        productRepository.save(new Product(1, "Fries", "Crispy fries", "20", "https://image.example/fries"));
        productRepository.save(new Product(2, "Burger", "Juicy burger", "30", "https://image.example/burger"));
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void shouldReturnSeededProductsFromRealDatabase() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Fries", "Burger")));
    }

    @Test
    void shouldCreateProductAndPersistIt() throws Exception {
        Product newProduct = new Product(100, "Pizza", "Cheese pizza", "25", "https://image.example/pizza");

        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newProduct)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(100)))
                .andExpect(jsonPath("$.name", is("Pizza")))
                .andExpect(jsonPath("$.description", is("Cheese pizza")))
                .andExpect(jsonPath("$.price", is("25")))
                .andExpect(jsonPath("$.image", is("https://image.example/pizza")));

        Product persistedProduct = productRepository.findById(100).orElse(null);
        assertNotNull(persistedProduct);
        assertEquals(3, productRepository.count());
        assertEquals("Pizza", persistedProduct.getName());
    }

    @Test
    void shouldReturnProductById() throws Exception {
        mockMvc.perform(get("/api/product/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Fries")))
                .andExpect(jsonPath("$.description", is("Crispy fries")))
                .andExpect(jsonPath("$.price", is("20")))
                .andExpect(jsonPath("$.image", is("https://image.example/fries")));
    }

    @Test
    void shouldReturnEmptyBodyWhenProductDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/product/999"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldUpdateProductAndPersistChangedFields() throws Exception {
        Product updatedProduct = new Product(1, "Curly Fries", "Crispy fries", "25", "https://image.example/curly-fries");

        mockMvc.perform(put("/api/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedProduct)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Curly Fries")))
                .andExpect(jsonPath("$.description", is("Crispy fries")))
                .andExpect(jsonPath("$.price", is("20")))
                .andExpect(jsonPath("$.image", is("https://image.example/fries")));

        Product persistedProduct = productRepository.findById(1).orElse(null);
        assertNotNull(persistedProduct);
        assertEquals("Curly Fries", persistedProduct.getName());
        assertEquals("Crispy fries", persistedProduct.getDescription());
        assertEquals("20", persistedProduct.getPrice());
        assertEquals("https://image.example/fries", persistedProduct.getImage());
    }

    @Test
    void shouldReturnBadRequestWhenUpdatingMissingProduct() throws Exception {
        Product missingProduct = new Product(999, "Ghost", "Missing product", "0", "https://image.example/ghost");

        mockMvc.perform(put("/api/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingProduct)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message", is("Cannot update product")));

        assertFalse(productRepository.findById(999).isPresent());
    }

    @Test
    void shouldDeleteExistingProduct() throws Exception {
        mockMvc.perform(delete("/api/delete/2"))
                .andExpect(status().isOk())
                .andExpect(content().string("Product deleted2"));

        assertFalse(productRepository.findById(2).isPresent());
        assertEquals(1, productRepository.count());
    }

    @Test
    void shouldReturnBadRequestWhenDeletingMissingProduct() throws Exception {
        mockMvc.perform(delete("/api/delete/999"))
                .andExpect(status().isBadRequest());

        assertEquals(2, productRepository.count());
    }

    @Test
    void shouldReturnBadRequestWhenCreatePayloadIsMalformed() throws Exception {
        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldKeepSeedDataAvailableInsideTransaction() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(1, 2)));
    }
}