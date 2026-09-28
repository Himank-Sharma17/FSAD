package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Feedback;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.FeedbackRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired FeedbackRepository feedbackRepository;
    @Autowired JwtService jwtService;

    private String adminToken;
    private String customerToken;
    private User buyer;

    @BeforeEach
    void setUp() {
        feedbackRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        adminToken = tokenFor(Role.ADMIN, "admin@shop.com");
        customerToken = tokenFor(Role.CUSTOMER, "cust@shop.com");
        buyer = persistUser("buyer@shop.com");
    }

    @AfterEach
    void tearDown() {
        feedbackRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String tokenFor(Role role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        return jwtService.generateToken(u);
    }

    private User persistUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Product persistProduct(String name, Category category, String price) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal(price));
        p.setQoh(100);
        p.setCategory(category);
        return productRepository.save(p);
    }

    private void seedPaidOrder(Product product, int qty, String price) {
        Order order = new Order();
        order.setUser(buyer);
        order.setStatus(OrderStatus.PAID);
        order.setStripeSessionId("cs_" + System.nanoTime());
        order.setOrderDate(Instant.now());
        order.setTotalAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(qty);
        item.setPriceAtPurchase(new BigDecimal(price));
        order.getItems().add(item);
        orderRepository.save(order);
    }

    private void seedFeedback(String message, int rating) {
        Feedback f = new Feedback();
        f.setUser(buyer);
        f.setMessage(message);
        f.setRating(rating);
        feedbackRepository.save(f);
    }

    @Test
    void salesReport_asAdmin_returnsTotalsAndByCategory() throws Exception {
        Product laptop = persistProduct("Laptop", Category.ELECTRONICS, "1000.00");
        seedPaidOrder(laptop, 2, "1000.00");

        mockMvc.perform(get("/api/admin/reports/sales?period=MONTH")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("MONTH"))
                .andExpect(jsonPath("$.totalItemsSold").value(2))
                .andExpect(jsonPath("$.totalRevenue").value(2000.00))
                .andExpect(jsonPath("$.totalPaidOrders").value(1))
                .andExpect(jsonPath("$.byCategory[0].label").value("ELECTRONICS"));
    }

    @Test
    void feedbackReport_asAdmin_returnsAggregates() throws Exception {
        seedFeedback("great", 5);
        seedFeedback("ok", 3);

        mockMvc.perform(get("/api/admin/reports/feedback")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.averageRating").value(4.0))
                .andExpect(jsonPath("$.ratingCounts.5").value(1))
                .andExpect(jsonPath("$.ratingCounts.3").value(1))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void feedbackReport_noFeedback_averageIsZero() throws Exception {
        mockMvc.perform(get("/api/admin/reports/feedback")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.averageRating").value(0.0))
                .andExpect(jsonPath("$.ratingCounts.1").value(0));
    }

    @Test
    void salesReport_asCustomer_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/reports/sales")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void feedbackReport_anonymous_returns4xx() throws Exception {
        mockMvc.perform(get("/api/admin/reports/feedback"))
                .andExpect(status().is4xxClientError());
    }
}
