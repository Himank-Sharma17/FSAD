package com.shop.onlineshopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.onlineshopping.dto.ProductRequest;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminProductControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        adminToken = tokenFor(Role.ADMIN, "admin@shop.com");
        customerToken = tokenFor(Role.CUSTOMER, "cust@shop.com");
    }

    private String tokenFor(Role role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        return jwtService.generateToken(u);
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    private ProductRequest validRequest() {
        return new ProductRequest("Laptop", "A laptop", new BigDecimal("999.99"), 5, Category.ELECTRONICS);
    }

    private Long createProductAsAdmin() throws Exception {
        String body = mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void create_asAdmin_returns201AndPersists() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Laptop"))
                .andExpect(jsonPath("$.category").value("ELECTRONICS"));

        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    void create_asCustomer_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_noToken_returns4xx() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void create_negativePrice_returns400() throws Exception {
        ProductRequest bad = new ProductRequest("Bad", "x", new BigDecimal("-1.00"), 5, Category.ELECTRONICS);
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest());
        assertThat(productRepository.count()).isZero();
    }

    @Test
    void create_negativeQoh_returns400() throws Exception {
        ProductRequest bad = new ProductRequest("Bad", "x", new BigDecimal("1.00"), -5, Category.ELECTRONICS);
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest());
        assertThat(productRepository.count()).isZero();
    }

    @Test
    void update_existing_asAdmin_returns200WithNewValues() throws Exception {
        Long id = createProductAsAdmin();

        ProductRequest update = new ProductRequest("Gaming Laptop", "Fast", new BigDecimal("1299.00"), 3, Category.ELECTRONICS);
        mockMvc.perform(put("/api/admin/products/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gaming Laptop"))
                .andExpect(jsonPath("$.qoh").value(3));
    }

    @Test
    void update_missing_returns404() throws Exception {
        mockMvc.perform(put("/api/admin/products/999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_existing_asAdmin_returns204() throws Exception {
        Long id = createProductAsAdmin();

        mockMvc.perform(delete("/api/admin/products/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(productRepository.existsById(id)).isFalse();
    }

    @Test
    void delete_missing_returns404() throws Exception {
        mockMvc.perform(delete("/api/admin/products/999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asCustomer_returns403() throws Exception {
        mockMvc.perform(delete("/api/admin/products/1")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_priceWithThreeDecimals_returns400() throws Exception {
        ProductRequest bad = new ProductRequest("Bad", "x", new BigDecimal("9.999"), 5, Category.ELECTRONICS);
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest());
        assertThat(productRepository.count()).isZero();
    }
}
