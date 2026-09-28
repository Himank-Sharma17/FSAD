package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired JwtService jwtService;

    private String customerToken;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        save("Laptop", Category.ELECTRONICS, "999.99", 5);
        save("Sofa", Category.FURNITURE, "499.00", 2);
        save("Lipstick", Category.COSMETICS, "12.50", 100);

        User customer = new User();
        customer.setEmail("cust@shop.com");
        customer.setRole(Role.CUSTOMER);
        customerToken = jwtService.generateToken(customer);
    }

    private void save(String name, Category category, String price, int qoh) {
        Product p = new Product();
        p.setName(name);
        p.setDescription(name + " description");
        p.setPrice(new BigDecimal(price));
        p.setQoh(qoh);
        p.setCategory(category);
        productRepository.save(p);
    }

    private String bearer() {
        return "Bearer " + customerToken;
    }

    @Test
    void listProducts_noToken_returns4xx() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listProducts_authenticated_returnsPagedProducts() throws Exception {
        mockMvc.perform(get("/api/products").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").exists())
                .andExpect(jsonPath("$.content[0].qoh").exists());
    }

    @Test
    void listProducts_filterByCategory_returnsOnlyThatCategory() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "ELECTRONICS")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Laptop"))
                .andExpect(jsonPath("$.content[0].category").value("ELECTRONICS"));
    }

    @Test
    void listProducts_invalidCategory_returns400() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "NOT_A_CATEGORY")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listProducts_pageBeyondRange_returnsEmptyContent() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "5").param("size", "2")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void getProduct_existing_returnsProduct() throws Exception {
        Long id = productRepository.findAll().get(0).getId();
        mockMvc.perform(get("/api/products/" + id).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.price").exists());
    }

    @Test
    void getProduct_missing_returns404() throws Exception {
        mockMvc.perform(get("/api/products/999999").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void listProducts_defaultsSortedByIdAscending() throws Exception {
        mockMvc.perform(get("/api/products").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Laptop"))
                .andExpect(jsonPath("$.content[2].name").value("Lipstick"));
    }
}
