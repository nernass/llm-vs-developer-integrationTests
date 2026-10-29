package com.example.productserviceshop.productTest;

import com.example.productserviceshop.entity.Product;
import com.example.productserviceshop.exception.RequestException;
import com.example.productserviceshop.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
public class deepseek {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    private Product sampleProduct;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();

        sampleProduct = new Product(1, "Burger", "Tasty burger", "12.99",
                "https://example.com/burger.jpg");
        sampleProduct = productRepository.save(sampleProduct);
    }

    @Test
    void shouldGetAllProducts_WhenProductsExist() throws Exception {
        mockMvc.perform(get("/api/products/"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Burger")))
                .andExpect(jsonPath("$[0].description", is("Tasty burger")))
                .andExpect(jsonPath("$[0].price", is("12.99")));
    }

    @Test
    void shouldGetAllProducts_WhenNoProducts_ReturnsEmptyList() throws Exception {
        productRepository.deleteAll();

        mockMvc.perform(get("/api/products/"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldGetProductById_WhenExists() throws Exception {
        mockMvc.perform(get("/api/product/{id}", sampleProduct.getId()))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(sampleProduct.getId())))
                .andExpect(jsonPath("$.name", is("Burger")))
                .andExpect(jsonPath("$.description", is("Tasty burger")))
                .andExpect(jsonPath("$.price", is("12.99")));
    }

    @Test
    void shouldGetProductById_WhenNotExists_ReturnsNull() throws Exception {
        int nonExistentId = 999;

        mockMvc.perform(get("/api/product/{id}", nonExistentId))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldCreateProduct_Successfully() throws Exception {
        Product newProduct = new Product(2, "Fries", "Crispy fries", "5.99",
                "https://example.com/fries.jpg");

        mockMvc.perform(post("/api/addProduct/")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(objectMapper.writeValueAsString(newProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("Fries")))
                .andExpect(jsonPath("$.description", is("Crispy fries")))
                .andExpect(jsonPath("$.price", is("5.99")));
    }

    @Test
    void shouldCreateProduct_WithEmptyProduct_ReturnsOk() throws Exception {
        Product emptyProduct = new Product();

        mockMvc.perform(post("/api/addProduct/")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(objectMapper.writeValueAsString(emptyProduct)))
                .andDo(print())
                .andExpect(status().isOk());
    }

    @Test
    void shouldUpdateProduct_Successfully() throws Exception {
        Product updated = new Product(sampleProduct.getId(), "Double Burger", "Double tasty burger", "15.99",
                "https://example.com/double.jpg");

        mockMvc.perform(put("/api/update/")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(objectMapper.writeValueAsString(updated)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(sampleProduct.getId())))
                .andExpect(jsonPath("$.name", is("Double Burger")));
    }

    @Test
    void shouldUpdateProduct_WhenNotExists_ThrowsRequestException() throws Exception {
        Product nonExistent = new Product(999, "Nonexistent", "Missing product", "0",
                "https://example.com/missing.jpg");

        mockMvc.perform(put("/api/update/")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content(objectMapper.writeValueAsString(nonExistent)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot update product",
                        result.getResolvedException().getMessage()));
    }

    @Test
    void shouldDeleteProduct_Successfully() throws Exception {
        mockMvc.perform(delete("/api/delete/{id}", sampleProduct.getId()))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo("Product deleted" + sampleProduct.getId())));

        assertTrue(productRepository.findById(sampleProduct.getId()).isEmpty());
    }

    @Test
    void shouldDeleteProduct_WhenNotExists_ThrowsRequestException() throws Exception {
        int nonExistentId = 999;

        mockMvc.perform(delete("/api/delete/{id}", nonExistentId))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException));
    }
}
