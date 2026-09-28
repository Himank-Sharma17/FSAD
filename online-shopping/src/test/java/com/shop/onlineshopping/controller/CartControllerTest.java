package com.shop.onlineshopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.onlineshopping.dto.AddCartItemRequest;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.CartItemRepository;
import com.shop.onlineshopping.repository.CartRepository;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.repository.UserRepository;
import com.shop.onlineshopping.service.CartService;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CartControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CartRepository cartRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired CartService cartService;
    @Autowired JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String customerToken;
    private User customer;
    private Product product;

    @BeforeEach
    void setUp() {
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        customer = newUser("cust@shop.com");
        product = newProduct("Laptop", 5);
        customerToken = jwtService.generateToken(customer);
    }

    @AfterEach
    void tearDown() {
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Customer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Product newProduct(String name, int qoh) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal("999.99"));
        p.setQoh(qoh);
        p.setCategory(Category.ELECTRONICS);
        return productRepository.save(p);
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    void addItemThenGetCart_showsItem() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AddCartItemRequest(product.getId(), 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.total").value(1999.98));

        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("Laptop"));
    }

    @Test
    void addItem_unknownProduct_returns404() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AddCartItemRequest(999999L, 1))))
                .andExpect(status().isNotFound());
    }

    @Test
    void addItem_zeroQuantity_returns400() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AddCartItemRequest(product.getId(), 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCart_anonymous_returns4xx() throws Exception {
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void removeItem_otherUsersItem_returns403() throws Exception {
        // Owner (a different customer) puts an item in their cart.
        User owner = newUser("owner@shop.com");
        cartService.addItem(owner, new AddCartItemRequest(product.getId(), 1));
        Long itemId = cartItemRepository.findAll().get(0).getId();

        // The attacker (setUp's customer) tries to delete it.
        mockMvc.perform(delete("/api/cart/items/" + itemId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }
}
