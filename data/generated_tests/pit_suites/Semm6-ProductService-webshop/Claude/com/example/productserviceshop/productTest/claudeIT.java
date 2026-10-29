package com.example.productserviceshop.productTest;

import com.example.productserviceshop.entity.Product;
import com.example.productserviceshop.exception.RequestException;
import com.example.productserviceshop.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@Transactional
public class claudeIT {

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

    @Test
    void shouldCreateProductAndReturn200() throws Exception {
        Product product = new Product(1, "Fries", "nice fries", "20", "https://image.com/fries");

        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Fries")))
                .andExpect(jsonPath("$.description", is("nice fries")))
                .andExpect(jsonPath("$.price", is("20")))
                .andExpect(jsonPath("$.image", is("https://image.com/fries")));

        assertEquals(1, productRepository.count());
    }

    @Test
    void shouldGetAllProductsWhenEmpty() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldGetAllProductsWhenMultipleExist() throws Exception {
        productRepository.save(new Product(1, "Fries", "nice fries", "20", "https://image.com/fries"));
        productRepository.save(new Product(2, "Burger", "juicy burger", "30", "https://image.com/burger"));

        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Fries", "Burger")));
    }

    @Test
    void shouldGetProductById() throws Exception {
        productRepository.save(new Product(1, "Fries", "nice fries", "20", "https://image.com/fries"));

        mockMvc.perform(get("/api/product/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Fries")))
                .andExpect(jsonPath("$.description", is("nice fries")));
    }

    @Test
    void shouldReturnNullBodyWhenProductNotFoundById() throws Exception {
        mockMvc.perform(get("/api/product/999"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldDeleteProductById() throws Exception {
        productRepository.save(new Product(1, "Fries", "nice fries", "20", "https://image.com/fries"));

        mockMvc.perform(delete("/api/delete/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo("Product deleted1")));

        assertEquals(0, productRepository.count());
    }

    @Test
    void shouldUpdateProductName() throws Exception {
        productRepository.save(new Product(1, "Fries", "nice fries", "20", "https://image.com/fries"));

        Product updatedProduct = new Product(1, "Curly Fries", "nice fries", "20", "https://image.com/fries");

        mockMvc.perform(put("/api/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Curly Fries")));

        Product saved = productRepository.findById(1).orElse(null);
        assertNotNull(saved);
        assertEquals("Curly Fries", saved.getName());
    }

    @Test
    void shouldReturn4xxWhenUpdateProductThatDoesNotExist() throws Exception {
        Product product = new Product(999, "Ghost", "does not exist", "0", "https://image.com/ghost");

        mockMvc.perform(put("/api/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot update product", result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturn4xxWhenDeleteProductThatDoesNotExist() throws Exception {
        mockMvc.perform(delete("/api/delete/999"))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot delete product", result.getResolvedException().getMessage()));
    }

    @Test
    void shouldCreateAndThenRetrieveProduct() throws Exception {
        Product product = new Product(10, "Pizza", "cheese pizza", "25", "https://image.com/pizza");

        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/product/10"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Pizza")))
                .andExpect(jsonPath("$.price", is("25")));
    }

    @Test
    void shouldCreateUpdateAndVerifyProduct() throws Exception {
        Product product = new Product(20, "Salad", "fresh salad", "15", "https://image.com/salad");

        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk());

        Product updated = new Product(20, "Caesar Salad", "fresh salad", "15", "https://image.com/salad");

        mockMvc.perform(put("/api/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/product/20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Caesar Salad")));
    }

    @Test
    void shouldCreateAndDeleteAndConfirmGone() throws Exception {
        Product product = new Product(30, "Soda", "cold soda", "5", "https://image.com/soda");

        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/delete/30"))
                .andExpect(status().isOk());

        assertFalse(productRepository.findById(30).isPresent());
    }
}
