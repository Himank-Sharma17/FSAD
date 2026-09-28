package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.OrderRepository;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.repository.UserRepository;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired JwtService jwtService;

    private User customer;
    private Product product;
    private String customerToken;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        customer = newUser("orders@shop.com");
        product = newProduct();
        customerToken = jwtService.generateToken(customer);
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Product newProduct() {
        Product p = new Product();
        p.setName("Laptop");
        p.setDescription("desc");
        p.setPrice(new BigDecimal("999.99"));
        p.setQoh(10);
        p.setCategory(Category.ELECTRONICS);
        return productRepository.save(p);
    }

    private Order newOrder(User user, String sessionId) {
        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PAID);
        order.setStripeSessionId(sessionId);
        order.setOrderDate(Instant.now());
        order.setTotalAmount(new BigDecimal("999.99"));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(1);
        item.setPriceAtPurchase(product.getPrice());
        order.getItems().add(item);
        return orderRepository.save(order);
    }

    @Test
    void listOrders_returnsOnlyOwnOrders() throws Exception {
        newOrder(customer, "cs_mine");
        newOrder(newUser("other@shop.com"), "cs_theirs");

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].items[0].productName").value("Laptop"));
    }

    @Test
    void listOrders_anonymous_returns4xx() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void deleteOrder_own_returns204() throws Exception {
        Order order = newOrder(customer, "cs_delete");
        mockMvc.perform(delete("/api/orders/" + order.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isNoContent());
        assertThat(orderRepository.existsById(order.getId())).isFalse();
    }

    @Test
    void deleteOrder_otherUsers_returns403() throws Exception {
        Order order = newOrder(newUser("victim@shop.com"), "cs_victim");
        mockMvc.perform(delete("/api/orders/" + order.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        assertThat(orderRepository.existsById(order.getId())).isTrue();
    }

    @Test
    void deleteOrder_missing_returns404() throws Exception {
        mockMvc.perform(delete("/api/orders/999999")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isNotFound());
    }
}
