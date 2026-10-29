
package com.example.productserviceshop.productTest;

import com.example.productserviceshop.entity.Product;
import com.example.productserviceshop.exception.RequestException;
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@Transactional
public class ProductIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        productRepository.deleteAll();
    }

    @Test
    void shouldReturnEmptyProductListWhenNoProductsExist() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void shouldCreateProductSuccessfully() throws Exception {
        Product product = new Product(1, "Fries", "nice fries", "20", "https://Niceimage.com");

        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Fries")));

        assertEquals(1, productRepository.findAll().size());
        assertEquals("Fries", productRepository.findById(1).orElseThrow().getName());
    }

    @Test
    void shouldGetProductByIdSuccessfully() throws Exception {
        Product product = new Product(1, "Burger", "tasty burger", "15", "https://burger.com");
        productRepository.save(product);

        mockMvc.perform(get("/api/product/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Burger")));
    }

    @Test
    void shouldUpdateProductSuccessfully() throws Exception {
        Product existingProduct = new Product(1, "Burger", "tasty burger", "15", "https://burger.com");
        productRepository.save(existingProduct);

        Product updatedProduct = new Product(1, "Cheeseburger", "tasty burger", "18", "https://burger.com");

        mockMvc.perform(put("/api/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Cheeseburger")));

        Product dbProduct = productRepository.findById(1).orElseThrow();
        assertEquals("Cheeseburger", dbProduct.getName());
    }

    @Test
    void shouldDeleteProductByIdSuccessfully() throws Exception {
        Product product = new Product(1, "Burger", "tasty burger", "15", "https://burger.com");
        productRepository.save(product);

        mockMvc.perform(delete("/api/delete/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo("Product deleted1")));

        assertTrue(productRepository.findById(1).isEmpty());
    }

    @Test
    void shouldReturnBadRequestWhenAddingMalformedProduct() throws Exception {
        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed JSON}"))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnBadRequestWhenUpdatingNonExistentProduct() throws Exception {
        Product nonExistentProduct = new Product(999, "Ghost", "Does not exist", "0", "");

        mockMvc.perform(put("/api/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistentProduct)))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot edit product", result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnBadRequestWhenDeletingNonExistentProduct() throws Exception {
        mockMvc.perform(delete("/api/delete/999"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot delete product", result.getResolvedException().getMessage()));
    }
}
