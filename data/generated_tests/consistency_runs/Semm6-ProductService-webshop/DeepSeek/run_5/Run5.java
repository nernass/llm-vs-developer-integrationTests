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

    private Product testProduct;

    @BeforeEach
    void setUp() {
        // Initialize test data
        testProduct = new Product(1, "Test Product", "Test Description", "99.99", "https://test-image.com");
    }

    @Test
    void shouldGetAllProductsWhenServiceReturnsList() throws Exception {
        // Arrange
        List<Product> products = Arrays.asList(
                testProduct,
                new Product(2, "Second Product", "Second Description", "49.99", "https://second-image.com")
        );
        when(productService.getProducts()).thenReturn(products);

        // Act & Assert
        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[0].name", is("Test Product")))
                .andExpect(jsonPath("$[1].id", is(2)))
                .andExpect(jsonPath("$[1].name", is("Second Product")));
    }

    @Test
    void shouldGetAllProductsWhenServiceReturnsEmptyList() throws Exception {
        // Arrange
        when(productService.getProducts()).thenReturn(List.of());

        // Act & Assert
        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldCreateProductWhenServiceSavesSuccessfully() throws Exception {
        // Arrange
        Product productToCreate = new Product(3, "New Product", "New Description", "75.50", "https://new-image.com");
        when(productService.saveProduct(any(Product.class))).thenReturn(productToCreate);

        // Act & Assert
        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(productToCreate)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(3)))
                .andExpect(jsonPath("$.name", is("New Product")))
                .andExpect(jsonPath("$.price", is("75.50")));
    }

    @Test
    void shouldGetProductByIdWhenProductExists() throws Exception {
        // Arrange
        when(productService.getProductById(1)).thenReturn(testProduct);

        // Act & Assert
        mockMvc.perform(get("/api/product/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Test Product")))
                .andExpect(jsonPath("$.description", is("Test Description")))
                .andExpect(jsonPath("$.price", is("99.99")))
                .andExpect(jsonPath("$.image", is("https://test-image.com")));
    }

    @Test
    void shouldReturnNullWhenProductNotFound() throws Exception {
        // Arrange
        when(productService.getProductById(999)).thenReturn(null);

        // Act & Assert
        mockMvc.perform(get("/api/product/999"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldUpdateProductWhenServiceSavesSuccessfully() throws Exception {
        // Arrange
        Product updatedProduct = new Product(1, "Updated Product", "Updated Description", "149.99", "https://updated-image.com");
        when(productService.updateProduct(any(Product.class))).thenReturn(updatedProduct);

        // Act & Assert
        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Updated Product")))
                .andExpect(jsonPath("$.price", is("149.99")));
    }

    @Test
    void shouldDeleteProductWhenServiceDeletesSuccessfully() throws Exception {
        // Arrange
        String deleteMessage = "Product deleted1";
        when(productService.deleteProduct(1)).thenReturn(deleteMessage);

        // Act & Assert
        mockMvc.perform(delete("/api/delete/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo(deleteMessage)));
    }

    @Test
    void shouldThrowRequestExceptionWhenGetAllProductsFails() throws Exception {
        // Arrange
        when(productService.getProducts()).thenThrow(new RequestException("Cannot get all products"));

        // Act & Assert
        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    org.assertj.core.api.Assertions.assertThat(resolvedException)
                            .isInstanceOf(RequestException.class)
                            .hasMessage("Cannot get all products");
                });
    }

    @Test
    void shouldThrowRequestExceptionWhenCreateProductFails() throws Exception {
        // Arrange
        when(productService.saveProduct(any(Product.class))).thenThrow(new RequestException("Product cannot be created"));

        // Act & Assert
        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testProduct)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    org.assertj.core.api.Assertions.assertThat(resolvedException)
                            .isInstanceOf(RequestException.class)
                            .hasMessage("Not able to add product");
                });
    }

    @Test
    void shouldThrowRequestExceptionWhenGetProductByIdFails() throws Exception {
        // Arrange
        when(productService.getProductById(anyInt())).thenThrow(new RequestException("Cannot get product by id"));

        // Act & Assert
        mockMvc.perform(get("/api/product/1"))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    org.assertj.core.api.Assertions.assertThat(resolvedException)
                            .isInstanceOf(RequestException.class)
                            .hasMessage("Cannot get product by id");
                });
    }

    @Test
    void shouldThrowRequestExceptionWhenUpdateProductFails() throws Exception {
        // Arrange
        when(productService.updateProduct(any(Product.class))).thenThrow(new RequestException("Cannot edit product"));

        // Act & Assert
        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testProduct)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    org.assertj.core.api.Assertions.assertThat(resolvedException)
                            .isInstanceOf(RequestException.class)
                            .hasMessage("Cannot update product");
                });
    }

    @Test
    void shouldThrowRequestExceptionWhenDeleteProductFails() throws Exception {
        // Arrange
        when(productService.deleteProduct(anyInt())).thenThrow(new RequestException("Cannot delete product"));

        // Act & Assert
        mockMvc.perform(delete("/api/delete/1"))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    Exception resolvedException = result.getResolvedException();
                    org.assertj.core.api.Assertions.assertThat(resolvedException)
                            .isInstanceOf(RequestException.class)
                            .hasMessage("Cannot delete product");
                });
    }
}