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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
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

    @BeforeEach
    void setUp() {
        sampleProduct = new Product(1, "Test Product", "Test Description", "29.99", "https://test-image.com");
        updatedProduct = new Product(1, "Updated Product", "Updated Description", "39.99", "https://updated-image.com");
    }

    @Test
    void shouldReturnAllProductsWhenProductsExist() throws Exception {
        List<Product> products = Arrays.asList(
                sampleProduct,
                new Product(2, "Product 2", "Description 2", "19.99", "https://image2.com")
        );

        when(productService.getProducts()).thenReturn(products);

        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[0].name", is("Test Product")))
                .andExpect(jsonPath("$[1].id", is(2)))
                .andExpect(jsonPath("$[1].name", is("Product 2")));

        verify(productService, times(1)).getProducts();
    }

    @Test
    void shouldReturnEmptyListWhenNoProductsExist() throws Exception {
        when(productService.getProducts()).thenReturn(List.of());

        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));

        verify(productService, times(1)).getProducts();
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
                .andExpect(jsonPath("$.price", is("29.99")));

        verify(productService, times(1)).saveProduct(any(Product.class));
    }

    @Test
    void shouldReturnProductByIdWhenProductExists() throws Exception {
        when(productService.getProductById(1)).thenReturn(sampleProduct);

        mockMvc.perform(get("/api/product/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Test Product")))
                .andExpect(jsonPath("$.description", is("Test Description")))
                .andExpect(jsonPath("$.price", is("29.99")));

        verify(productService, times(1)).getProductById(1);
    }

    @Test
    void shouldReturnNullWhenProductNotFoundById() throws Exception {
        when(productService.getProductById(999)).thenReturn(null);

        mockMvc.perform(get("/api/product/999"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string("null"));

        verify(productService, times(1)).getProductById(999);
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
                .andExpect(jsonPath("$.price", is("39.99")));

        verify(productService, times(1)).updateProduct(any(Product.class));
    }

    @Test
    void shouldDeleteProductSuccessfully() throws Exception {
        String deleteMessage = "Product deleted1";
        when(productService.deleteProduct(1)).thenReturn(deleteMessage);

        mockMvc.perform(delete("/api/delete/1"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Product deleted")))
                .andExpect(content().string(containsString("1")));

        verify(productService, times(1)).deleteProduct(1);
    }

    @Test
    void shouldReturnErrorMessageWhenServiceThrowsExceptionOnGetAllProducts() throws Exception {
        when(productService.getProducts()).thenThrow(new RequestException("Cannot get all products"));

        mockMvc.perform(get("/api/products"))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot get all products".equals(result.getResolvedException().getMessage()));

        verify(productService, times(1)).getProducts();
    }

    @Test
    void shouldReturnErrorMessageWhenServiceThrowsExceptionOnGetProductById() throws Exception {
        when(productService.getProductById(1)).thenThrow(new RequestException("Cannot get product by id"));

        mockMvc.perform(get("/api/product/1"))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot get product by id".equals(result.getResolvedException().getMessage()));

        verify(productService, times(1)).getProductById(1);
    }

    @Test
    void shouldReturnErrorMessageWhenServiceThrowsExceptionOnDeleteProduct() throws Exception {
        when(productService.deleteProduct(1)).thenThrow(new RequestException("Cannot delete product"));

        mockMvc.perform(delete("/api/delete/1"))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot delete product".equals(result.getResolvedException().getMessage()));

        verify(productService, times(1)).deleteProduct(1);
    }

    @Test
    void shouldReturnErrorMessageWhenServiceThrowsExceptionOnCreateProduct() throws Exception {
        when(productService.saveProduct(any(Product.class))).thenThrow(new RequestException("Product cannot be created"));

        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleProduct)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Product cannot be created".equals(result.getResolvedException().getMessage()));

        verify(productService, times(1)).saveProduct(any(Product.class));
    }

    @Test
    void shouldReturnErrorMessageWhenServiceThrowsExceptionOnUpdateProduct() throws Exception {
        when(productService.updateProduct(any(Product.class))).thenThrow(new RequestException("Cannot edit product"));

        mockMvc.perform(put("/api/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedProduct)))
                .andDo(print())
                .andExpect(status().is4xxClientError())
                .andExpect(result -> assert result.getResolvedException() instanceof RequestException)
                .andExpect(result -> "Cannot edit product".equals(result.getResolvedException().getMessage()));

        verify(productService, times(1)).updateProduct(any(Product.class));
    }

    @Test
    void shouldHandleProductWithNullFieldsInCreate() throws Exception {
        Product productWithNulls = new Product(3, null, null, null, null);
        when(productService.saveProduct(any(Product.class))).thenReturn(productWithNulls);

        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(productWithNulls)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(3)))
                .andExpect(jsonPath("$.name").doesNotExist());

        verify(productService, times(1)).saveProduct(any(Product.class));
    }

    @Test
    void shouldHandleLargeProductDataInCreate() throws Exception {
        String largeName = "A".repeat(1000);
        String largeDescription = "B".repeat(5000);
        Product largeProduct = new Product(5, largeName, largeDescription, "99.99", "https://large-image.com");

        when(productService.saveProduct(any(Product.class))).thenReturn(largeProduct);

        mockMvc.perform(post("/api/addProduct")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(largeProduct)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(5)))
                .andExpect(jsonPath("$.name", is(largeName)))
                .andExpect(jsonPath("$.description", is(largeDescription)));

        verify(productService, times(1)).saveProduct(any(Product.class));
    }

    @Test
    void shouldHandleMultipleConcurrentGetRequests() throws Exception {
        when(productService.getProducts()).thenReturn(Arrays.asList(sampleProduct));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id", is(1)));
        }

        verify(productService, times(3)).getProducts();
    }
}