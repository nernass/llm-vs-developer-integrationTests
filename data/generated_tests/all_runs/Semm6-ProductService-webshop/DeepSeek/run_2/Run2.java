package com.example.productserviceshop.controller;

import com.example.productserviceshop.entity.Product;
import com.example.productserviceshop.exception.RequestException;
import com.example.productserviceshop.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
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
        testProduct1 = new Product(1, "Laptop", "High performance laptop", "999.99", "https://example.com/laptop.jpg");
        testProduct2 = new Product(2, "Mouse", "Wireless mouse", "29.99", "https://example.com/mouse.jpg");
    }

    @Test
    void shouldReturnAllProductsWhenProductsExist() throws Exception {
        List<Product> products = Arrays.asList(testProduct1, testProduct2);
        when(productService.getProducts()).thenReturn(products);

        mockMvc.perform(get("/api/products")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[0].name", is("Laptop")))
                .andExpect(jsonPath("$[1].id", is(2)))
                .andExpect(jsonPath("$[1].name", is("Mouse")));
    }

    @Test
    void shouldReturnEmptyListWhenNoProductsExist() throws Exception {
        when(productService.getProducts()).thenReturn(Arrays.asList());

        mockMvc.perform(get("/api/products")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldCreateProductSuccessfully() throws Exception {
        when(productService.saveProduct(any(Product.class))).thenReturn(testProduct1);

        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testProduct1)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Laptop")))
                .andExpect(jsonPath("$.description", is("High performance laptop")))
                .andExpect(jsonPath("$.price", is("999.99")))
                .andExpect(jsonPath("$.image", is("https://example.com/laptop.jpg")));
    }

    @Test
    void shouldReturnProductByIdWhenProductExists() throws Exception {
        when(productService.getProductById(1)).thenReturn(testProduct1);

        mockMvc.perform(get("/api/product/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Laptop")))
                .andExpect(jsonPath("$.description", is("High performance laptop")))
                .andExpect(jsonPath("$.price", is("999.99")))
                .andExpect(jsonPath("$.image", is("https://example.com/laptop.jpg")));
    }

    @Test
    void shouldReturnNullWhenProductByIdNotFound() throws Exception {
        when(productService.getProductById(99)).thenReturn(null);

        mockMvc.perform(get("/api/product/{id}", 99)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldUpdateProductSuccessfully() throws Exception {
        Product updatedProduct = new Product(1, "Gaming Laptop", "High performance gaming laptop", "1299.99", "https://example.com/gaming-laptop.jpg");
        when(productService.updateProduct(any(Product.class))).thenReturn(updatedProduct);

        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Gaming Laptop")))
                .andExpect(jsonPath("$.description", is("High performance gaming laptop")))
                .andExpect(jsonPath("$.price", is("1299.99")))
                .andExpect(jsonPath("$.image", is("https://example.com/gaming-laptop.jpg")));
    }

    @Test
    void shouldDeleteProductSuccessfully() throws Exception {
        when(productService.deleteProduct(1)).thenReturn("Product deleted1");

        mockMvc.perform(delete("/api/delete/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string("Product deleted1"));
    }

    @Test
    void shouldReturnExceptionWhenGetAllProductsFails() throws Exception {
        when(productService.getProducts()).thenThrow(new RequestException("Cannot get all products"));

        mockMvc.perform(get("/api/products")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    if (resolvedException == null) {
                        throw new AssertionError("Expected RequestException but got null");
                    }
                    if (!(resolvedException instanceof RequestException)) {
                        throw new AssertionError("Expected RequestException but got " + resolvedException.getClass());
                    }
                    if (!"Cannot get all products".equals(resolvedException.getMessage())) {
                        throw new AssertionError("Expected message 'Cannot get all products' but got '" + resolvedException.getMessage() + "'");
                    }
                });
    }

    @Test
    void shouldReturnExceptionWhenGetProductByIdFails() throws Exception {
        when(productService.getProductById(anyInt())).thenThrow(new RequestException("Cannot get product by id"));

        mockMvc.perform(get("/api/product/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    if (resolvedException == null) {
                        throw new AssertionError("Expected RequestException but got null");
                    }
                    if (!(resolvedException instanceof RequestException)) {
                        throw new AssertionError("Expected RequestException but got " + resolvedException.getClass());
                    }
                    if (!"Cannot get product by id".equals(resolvedException.getMessage())) {
                        throw new AssertionError("Expected message 'Cannot get product by id' but got '" + resolvedException.getMessage() + "'");
                    }
                });
    }

    @Test
    void shouldReturnExceptionWhenDeleteProductFails() throws Exception {
        when(productService.deleteProduct(anyInt())).thenThrow(new RequestException("Cannot delete product"));

        mockMvc.perform(delete("/api/delete/{id}", 1)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    if (resolvedException == null) {
                        throw new AssertionError("Expected RequestException but got null");
                    }
                    if (!(resolvedException instanceof RequestException)) {
                        throw new AssertionError("Expected RequestException but got " + resolvedException.getClass());
                    }
                    if (!"Cannot delete product".equals(resolvedException.getMessage())) {
                        throw new AssertionError("Expected message 'Cannot delete product' but got '" + resolvedException.getMessage() + "'");
                    }
                });
    }

    @Test
    void shouldReturnExceptionWhenCreateProductFails() throws Exception {
        when(productService.saveProduct(any(Product.class))).thenThrow(new RequestException("Product cannot be created"));

        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testProduct1)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    if (resolvedException == null) {
                        throw new AssertionError("Expected RequestException but got null");
                    }
                    if (!(resolvedException instanceof RequestException)) {
                        throw new AssertionError("Expected RequestException but got " + resolvedException.getClass());
                    }
                    if (!"Not able to add product".equals(resolvedException.getMessage())) {
                        throw new AssertionError("Expected message 'Not able to add product' but got '" + resolvedException.getMessage() + "'");
                    }
                });
    }

    @Test
    void shouldReturnExceptionWhenUpdateProductFails() throws Exception {
        when(productService.updateProduct(any(Product.class))).thenThrow(new RequestException("Cannot edit product"));

        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testProduct1)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    if (resolvedException == null) {
                        throw new AssertionError("Expected RequestException but got null");
                    }
                    if (!(resolvedException instanceof RequestException)) {
                        throw new AssertionError("Expected RequestException but got " + resolvedException.getClass());
                    }
                    if (!"Cannot update product".equals(resolvedException.getMessage())) {
                        throw new AssertionError("Expected message 'Cannot update product' but got '" + resolvedException.getMessage() + "'");
                    }
                });
    }
}