package com.example.productserviceshop.productTest;

import com.example.productserviceshop.controller.ProductController;
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

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
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

    private Product sampleProduct;
    private Product updatedProduct;
    private List<Product> productList;

    @BeforeEach
    void setUp() {
        sampleProduct = new Product(1, "Test Product", "Test Description", "99.99", "https://test.com/image.jpg");
        updatedProduct = new Product(1, "Updated Product", "Updated Description", "149.99", "https://test.com/updated.jpg");
        productList = Arrays.asList(
                sampleProduct,
                new Product(2, "Second Product", "Second Description", "49.99", "https://test.com/second.jpg")
        );
    }

    @Test
    void shouldReturnAllProductsWhenListNotEmpty() throws Exception {
        when(productService.getProducts()).thenReturn(productList);

        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[0].name", is("Test Product")))
                .andExpect(jsonPath("$[1].id", is(2)))
                .andExpect(jsonPath("$[1].name", is("Second Product")));
    }

    @Test
    void shouldReturnEmptyListWhenNoProductsExist() throws Exception {
        when(productService.getProducts()).thenReturn(Arrays.asList());

        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldCreateProductSuccessfully() throws Exception {
        when(productService.saveProduct(any(Product.class))).thenReturn(sampleProduct);

        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Test Product")))
                .andExpect(jsonPath("$.description", is("Test Description")))
                .andExpect(jsonPath("$.price", is("99.99")))
                .andExpect(jsonPath("$.image", is("https://test.com/image.jpg")));
    }

    @Test
    void shouldReturnProductWhenIdExists() throws Exception {
        when(productService.getProductById(1)).thenReturn(sampleProduct);

        mockMvc.perform(get("/api/product/{id}", 1))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Test Product")))
                .andExpect(jsonPath("$.description", is("Test Description")));
    }

    @Test
    void shouldReturnNullWhenProductNotFound() throws Exception {
        when(productService.getProductById(999)).thenReturn(null);

        mockMvc.perform(get("/api/product/{id}", 999))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
    }

    @Test
    void shouldUpdateProductSuccessfully() throws Exception {
        when(productService.updateProduct(any(Product.class))).thenReturn(updatedProduct);

        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Updated Product")))
                .andExpect(jsonPath("$.description", is("Updated Description")))
                .andExpect(jsonPath("$.price", is("149.99")))
                .andExpect(jsonPath("$.image", is("https://test.com/updated.jpg")));
    }

    @Test
    void shouldDeleteProductSuccessfully() throws Exception {
        when(productService.deleteProduct(1)).thenReturn("Product deleted1");

        mockMvc.perform(delete("/api/delete/{id}", 1))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Product deleted1")));
    }

    @Test
    void shouldReturnRequestExceptionWhenGetAllProductsFails() throws Exception {
        when(productService.getProducts()).thenThrow(new RequestException("Cannot get all products"));

        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot get all products".equals(result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnRequestExceptionWhenGetProductByIdFails() throws Exception {
        when(productService.getProductById(anyInt())).thenThrow(new RequestException("Cannot get product by id"));

        mockMvc.perform(get("/api/product/{id}", 1))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot get product by id".equals(result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnRequestExceptionWhenCreateProductFails() throws Exception {
        when(productService.saveProduct(any(Product.class))).thenThrow(new RequestException("Product cannot be created"));

        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleProduct)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Not able to add product".equals(result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnRequestExceptionWhenUpdateProductFails() throws Exception {
        when(productService.updateProduct(any(Product.class))).thenThrow(new RequestException("Cannot edit product"));

        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot update product".equals(result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnRequestExceptionWhenDeleteProductFails() throws Exception {
        when(productService.deleteProduct(anyInt())).thenThrow(new RequestException("Cannot delete product"));

        mockMvc.perform(delete("/api/delete/{id}", 1))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot delete product".equals(result.getResolvedException().getMessage()));
    }

    @Test
    void shouldReturnRequestExceptionWhenProductNotFoundForUpdate() throws Exception {
        when(productService.updateProduct(any(Product.class)))
                .thenThrow(new RequestException("Cannot edit product"));

        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new Product(999, "Non-existent", "N/A", "0", "N/A"))))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot update product".equals(result.getResolvedException().getMessage()));
    }
}