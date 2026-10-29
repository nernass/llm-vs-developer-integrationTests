package com.example.productserviceshop.productTest;

import com.example.productserviceshop.entity.Product;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@Transactional
public class geminiIT_2 {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void shouldCreateProductAndReturnOk() throws Exception {
        Product newProduct = new Product(1, "Burger", "Delicious burger", "15", "http://image.com/burger");

        mockMvc.perform(post("/api/addProduct/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newProduct)))
                .andDo(print())
                .andExpect(status().isOk());
    }

    @Test
    void shouldGetAllProducts() throws Exception {
        Product product = new Product(2, "Pizza", "Cheese pizza", "25", "http://image.com/pizza");
        mockMvc.perform(post("/api/addProduct/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/products/"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[*].name", hasItem("Pizza")));
    }

    @Test
    void shouldGetProductById() throws Exception {
        Product product = new Product(3, "Pasta", "Tomato pasta", "18", "http://image.com/pasta");
        mockMvc.perform(post("/api/addProduct/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/product/" + 3))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Pasta")));
    }

    @Test
    void shouldUpdateProduct() throws Exception {
        Product product = new Product(4, "Salad", "Healthy salad", "10", "http://image.com/salad");
        mockMvc.perform(post("/api/addProduct/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk());

        Product updatedProduct = new Product(4, "Updated Salad", "Healthy salad", "10", "http://image.com/salad");
        mockMvc.perform(put("/api/update/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/product/" + 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Updated Salad")))
                .andExpect(jsonPath("$.price", is("10")));
    }

    @Test
    void shouldDeleteProductById() throws Exception {
        Product product = new Product(5, "Soda", "Cold soda", "5", "http://image.com/soda");
        mockMvc.perform(post("/api/addProduct/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/delete/" + 5))
                .andDo(print())
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/product/" + 5))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldReturn4xxWhenProductNotFound() throws Exception {
        mockMvc.perform(get("/api/product/999"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }
}