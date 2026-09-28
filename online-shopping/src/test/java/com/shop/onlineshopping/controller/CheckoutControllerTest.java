package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.dto.AddCartItemRequest;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.CartItemRepository;
import com.shop.onlineshopping.repository.CartRepository;
import com.shop.onlineshopping.repository.OrderRepository;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.repository.UserRepository;
import com.shop.onlineshopping.service.CartService;
import com.shop.onlineshopping.service.JwtService;
import com.shop.onlineshopping.service.StripeGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CartRepository cartRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired CartService cartService;
    @Autowired JwtService jwtService;

    @MockitoBean StripeGateway stripeGateway;

    private User customer;
    private Product product;
    private String customerToken;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        customer = newUser("checkout@shop.com");
        product = newProduct(5);
        customerToken = jwtService.generateToken(customer);
    }

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Product newProduct(int qoh) {
        Product p = new Product();
        p.setName("Laptop");
        p.setDescription("desc");
        p.setPrice(new BigDecimal("999.99"));
        p.setQoh(qoh);
        p.setCategory(Category.ELECTRONICS);
        return productRepository.save(p);
    }

    @Test
    void checkout_withStockedCart_returnsCheckoutUrl() throws Exception {
        cartService.addItem(customer, new AddCartItemRequest(product.getId(), 2));
        when(stripeGateway.createCheckoutSession(any()))
                .thenReturn(new StripeGateway.StripeSession("cs_test_ok", "https://checkout.stripe/pay"));

        mockMvc.perform(post("/api/checkout").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.stripe/pay"));
    }

    @Test
    void checkout_emptyCart_returns400AndNoOrderNoSession() throws Exception {
        cartService.getCart(customer); // creates an empty cart

        mockMvc.perform(post("/api/checkout").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(orderRepository.count()).isZero();
        verifyNoInteractions(stripeGateway);
    }

    @Test
    void checkout_quantityExceedsStock_returns409AndNoOrderNoSession() throws Exception {
        cartService.addItem(customer, new AddCartItemRequest(product.getId(), 10)); // qoh is 5

        mockMvc.perform(post("/api/checkout").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(orderRepository.count()).isZero();
        verifyNoInteractions(stripeGateway);
    }

    @Test
    void checkout_anonymous_returns4xx() throws Exception {
        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().is4xxClientError());
    }
}
