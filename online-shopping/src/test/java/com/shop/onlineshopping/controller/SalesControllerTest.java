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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SalesControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired JwtService jwtService;

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        adminToken = tokenFor(Role.ADMIN, "admin@shop.com");
        customerToken = tokenFor(Role.CUSTOMER, "cust@shop.com");
    }

    @AfterEach
    void tearDown() {
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

    private void seedPaidOrder(User user, Product product, int qty, String price) {
        Order order = new Order();
        order.setUser(user);
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

    private Product persistProduct(String name, Category category, String price) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal(price));
        p.setQoh(100);
        p.setCategory(category);
        return productRepository.save(p);
    }

    @Test
    void sales_byProduct_asAdmin_returnsAggregatedLines() throws Exception {
        User user = persistUser("buyer@shop.com");
        Product laptop = persistProduct("Laptop", Category.ELECTRONICS, "1000.00");
        seedPaidOrder(user, laptop, 2, "1000.00");

        mockMvc.perform(get("/api/admin/sales?period=MONTH&groupBy=PRODUCT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Laptop"))
                .andExpect(jsonPath("$[0].totalQuantity").value(2))
                .andExpect(jsonPath("$[0].totalRevenue").value(2000.00));
    }

    @Test
    void sales_byCategory_asAdmin_labelsByCategoryName() throws Exception {
        User user = persistUser("buyer@shop.com");
        Product laptop = persistProduct("Laptop", Category.ELECTRONICS, "1000.00");
        seedPaidOrder(user, laptop, 1, "1000.00");

        mockMvc.perform(get("/api/admin/sales?period=YEAR&groupBy=CATEGORY")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("ELECTRONICS"));
    }

    @Test
    void movement_asAdmin_returnsRanking() throws Exception {
        User user = persistUser("buyer@shop.com");
        Product laptop = persistProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Product lamp = persistProduct("Lamp", Category.ELECTRICAL, "50.00");
        seedPaidOrder(user, laptop, 2, "1000.00");
        seedPaidOrder(user, lamp, 10, "50.00");

        mockMvc.perform(get("/api/admin/sales/movement?period=MONTH")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Lamp"))
                .andExpect(jsonPath("$[0].totalQuantitySold").value(10));
    }

    @Test
    void sales_emptyPeriod_returns200EmptyList() throws Exception {
        mockMvc.perform(get("/api/admin/sales?period=WEEK&groupBy=PRODUCT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sales_asCustomer_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/sales")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void sales_anonymous_returns4xx() throws Exception {
        mockMvc.perform(get("/api/admin/sales"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void sales_invalidPeriod_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/sales?period=DECADE&groupBy=PRODUCT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }
}
