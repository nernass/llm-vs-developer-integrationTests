package com.example.productserviceshop.productTest;

import com.example.productserviceshop.controller.ProductController;
import com.example.productserviceshop.entity.Product;
import com.example.productserviceshop.exception.RequestException;
import com.example.productserviceshop.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
public class ProductIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    private Product testProduct1;
    private Product testProduct2;

    @BeforeEach
    void setUp() {
        // Setup seed data for tests
        testProduct1 = new Product(1, "Laptop", "High performance laptop", "999.99", "https://image.url/laptop.jpg");
        testProduct2 = new Product(2, "Mouse", "Wireless mouse", "29.99", "https://image.url/mouse.jpg");
    }

    @AfterEach
    void tearDown() {
        // Clean up any test artifacts if needed
        // No external resources to clean in this test
    }

    @Test
    void shouldReturnAllProductsWhenGetProducts() throws Exception {
        // Arrange
        List<Product> products = Arrays.asList(testProduct1, testProduct2);
        when(productService.getProducts()).thenReturn(products);

        // Act & Assert
        mockMvc.perform(get("/api/products")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Laptop"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].name").value("Mouse"));
    }

    @Test
    void shouldReturnEmptyListWhenNoProductsExist() throws Exception {
        // Arrange
        when(productService.getProducts()).thenReturn(Arrays.asList());

        // Act & Assert
        mockMvc.perform(get("/api/products")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldCreateProductWhenPostValidProduct() throws Exception {
        // Arrange
        Product newProduct = new Product(3, "Keyboard", "Mechanical keyboard", "89.99", "https://image.url/keyboard.jpg");
        when(productService.saveProduct(any(Product.class))).thenReturn(newProduct);

        // Act & Assert
        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andExpect(jsonPath("$.price").value("89.99"));
    }

    @Test
    void shouldReturnProductWhenGetProductById() throws Exception {
        // Arrange
        when(productService.getProductById(1)).thenReturn(testProduct1);

        // Act & Assert
        mockMvc.perform(get("/api/product/1")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Laptop"))
                .andExpect(jsonPath("$.description").value("High performance laptop"));
    }

    @Test
    void shouldReturnNullWhenGetProductByIdNotFound() throws Exception {
        // Arrange
        when(productService.getProductById(999)).thenReturn(null);

        // Act & Assert
        mockMvc.perform(get("/api/product/999")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldUpdateProductWhenPutValidProduct() throws Exception {
        // Arrange
        Product updatedProduct = new Product(1, "Gaming Laptop", "High performance gaming laptop", "1299.99", "https://image.url/gaming-laptop.jpg");
        when(productService.updateProduct(any(Product.class))).thenReturn(updatedProduct);

        // Act & Assert
        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Gaming Laptop"))
                .andExpect(jsonPath("$.price").value("1299.99"));
    }

    @Test
    void shouldDeleteProductWhenDeleteById() throws Exception {
        // Arrange
        when(productService.deleteProduct(1)).thenReturn("Product deleted" + 1);

        // Act & Assert
        mockMvc.perform(delete("/api/delete/1")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo("Product deleted" + 1)));
    }

    @Test
    void shouldReturnErrorMessageWhenGetAllProductsThrowsException() throws Exception {
        // Arrange
        when(productService.getProducts()).thenThrow(new RequestException("Cannot get all products"));

        // Act & Assert
        mockMvc.perform(get("/api/products")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot get all products", result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnErrorMessageWhenGetProductByIdThrowsException() throws Exception {
        // Arrange
        when(productService.getProductById(anyInt())).thenThrow(new RequestException("Cannot get product by id"));

        // Act & Assert
        mockMvc.perform(get("/api/product/1")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot get product by id", result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnErrorMessageWhenDeleteProductThrowsException() throws Exception {
        // Arrange
        when(productService.deleteProduct(anyInt())).thenThrow(new RequestException("Cannot delete product"));

        // Act & Assert
        mockMvc.perform(delete("/api/delete/1")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot delete product", result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnErrorMessageWhenAddProductThrowsException() throws Exception {
        // Arrange
        Product newProduct = new Product(4, "Monitor", "4K Monitor", "399.99", "https://image.url/monitor.jpg");
        when(productService.saveProduct(any(Product.class))).thenThrow(new RequestException("Product cannot be created"));

        // Act & Assert
        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newProduct)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Product cannot be created", result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnErrorMessageWhenUpdateProductThrowsException() throws Exception {
        // Arrange
        Product updatedProduct = new Product(1, "Updated Laptop", "Updated description", "1499.99", "https://image.url/updated-laptop.jpg");
        when(productService.updateProduct(any(Product.class))).thenThrow(new RequestException("Cannot edit product"));

        // Act & Assert
        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assertTrue(result.getResolvedException() instanceof RequestException))
                .andExpect(result -> assertEquals("Cannot edit product", result.getResolvedException().getMessage()));
    }
}