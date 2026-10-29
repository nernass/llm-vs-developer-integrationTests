
package com.example.productserviceshop.productTest;

import com.example.productserviceshop.controller.ProductController;
import com.example.productserviceshop.entity.Product;
import com.example.productserviceshop.exception.ApiExceptionHandler;
import com.example.productserviceshop.exception.RequestException;
import com.example.productserviceshop.service.ProductService;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProductController.class)
@Import({ApiExceptionHandler.class, ProductIntegrationTest.JacksonFieldVisibilityConfig.class})
public class ProductIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    void shouldReturnEmptyProductListWhenNoProductsExist() throws Exception {
        List<Product> products = Collections.emptyList();

        when(productService.getProducts()).thenReturn(products);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(productService).getProducts();
    }

    @Test
    void shouldCreateProductSuccessfully() throws Exception {
        Product product = new Product(1, "Fries", "nice fries", "20", "https://Niceimage.com");

        when(productService.saveProduct(any(Product.class))).thenReturn(product);

        mockMvc.perform(post("/api/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(product)));

        verify(productService).saveProduct(any(Product.class));
    }

    @Test
    void shouldDeleteProductByIdSuccessfully() throws Exception {
        when(productService.deleteProduct(1)).thenReturn("Product deleted1");

        mockMvc.perform(delete("/api/delete/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(equalTo("Product deleted1")));

        verify(productService).deleteProduct(1);
    }

    @Test
    void shouldGetProductByIdSuccessfully() throws Exception {
        Product product = new Product(1, "Fries", "nice fries", "20", "https://Niceimage.com");

        when(productService.getProductById(1)).thenReturn(product);

        mockMvc.perform(get("/api/product/1"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(product)));

        verify(productService).getProductById(1);
    }

    @Test
    void shouldUpdateProductSuccessfully() throws Exception {
        Product product = new Product(1, "Fries", "nice fries", "20", "https://Niceimage.com");

        when(productService.updateProduct(any(Product.class))).thenReturn(product);

        mockMvc.perform(put("/api/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(product)));

        verify(productService).updateProduct(any(Product.class));
    }

    @Test
    void shouldReturnBadRequestWhenGettingAllProductsFails() throws Exception {
        when(productService.getProducts()).thenThrow(new RequestException("Cannot get all students"));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    assertInstanceOf(RequestException.class, result.getResolvedException());
                    assertEquals("Cannot get all products", result.getResolvedException().getMessage());
                });
    }

    @Test
    void shouldReturnBadRequestWhenGettingProductByIdFails() throws Exception {
        when(productService.getProductById(1)).thenThrow(new RequestException("Cannot get product by id"));

        mockMvc.perform(get("/api/product/1"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    assertInstanceOf(RequestException.class, result.getResolvedException());
                    assertEquals("Cannot get product by id", result.getResolvedException().getMessage());
                });
    }

    @Test
    void shouldReturnBadRequestWhenDeletingProductByIdFails() throws Exception {
        when(productService.deleteProduct(1)).thenThrow(new RequestException("Cannot delete product"));

        mockMvc.perform(delete("/api/delete/1"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    assertInstanceOf(RequestException.class, result.getResolvedException());
                    assertEquals("Cannot delete product", result.getResolvedException().getMessage());
                });
    }

    @TestConfiguration
    static class JacksonFieldVisibilityConfig {

        @Bean
        Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
            return builder -> builder.visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        }
    }
}