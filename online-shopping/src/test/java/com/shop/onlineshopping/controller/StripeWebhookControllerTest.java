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
import com.shop.onlineshopping.service.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StripeWebhookControllerTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret"; // matches application-test.properties
    private static final String SESSION_ID = "cs_test_123";
    private static final String EVENT_JSON = """
            {"id":"evt_test","object":"event","type":"checkout.session.completed",\
            "data":{"object":{"id":"cs_test_123","object":"checkout.session"}}}""";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;

    @MockitoBean EmailService emailService;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setEmail("buyer@shop.com");
        user.setPasswordHash("hash");
        user.setFullName("Buyer");
        user.setRole(Role.CUSTOMER);
        user = userRepository.save(user);

        Product product = new Product();
        product.setName("Laptop");
        product.setDescription("desc");
        product.setPrice(new BigDecimal("999.99"));
        product.setQoh(5);
        product.setCategory(Category.ELECTRONICS);
        product = productRepository.save(product);

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setStripeSessionId(SESSION_ID);
        order.setOrderDate(Instant.now());
        order.setTotalAmount(new BigDecimal("1999.98"));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(2);
        item.setPriceAtPurchase(product.getPrice());
        order.getItems().add(item);
        orderRepository.save(order);
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String signature(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) {
            hex.append(String.format("%02x", b));
        }
        return "t=" + timestamp + ",v1=" + hex;
    }

    private int qoh() {
        return productRepository.findAll().get(0).getQoh();
    }

    private OrderStatus orderStatus() {
        return orderRepository.findByStripeSessionId(SESSION_ID).orElseThrow().getStatus();
    }

    @Test
    void validSignedEvent_marksPaidAndDecrementsStock() throws Exception {
        mockMvc.perform(post("/api/stripe/webhook")
                        .header("Stripe-Signature", signature(EVENT_JSON))
                        .content(EVENT_JSON))
                .andExpect(status().isOk());

        assertThat(orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(qoh()).isEqualTo(3); // 5 - 2
    }

    @Test
    void duplicateEvent_isIdempotent_noDoubleDecrement() throws Exception {
        mockMvc.perform(post("/api/stripe/webhook")
                        .header("Stripe-Signature", signature(EVENT_JSON))
                        .content(EVENT_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stripe/webhook")
                        .header("Stripe-Signature", signature(EVENT_JSON))
                        .content(EVENT_JSON))
                .andExpect(status().isOk());

        assertThat(orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(qoh()).isEqualTo(3); // decremented exactly once
    }

    @Test
    void invalidSignature_returns400AndLeavesOrderUntouched() throws Exception {
        mockMvc.perform(post("/api/stripe/webhook")
                        .header("Stripe-Signature", "t=1,v1=deadbeef")
                        .content(EVENT_JSON))
                .andExpect(status().isBadRequest());

        assertThat(orderStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(qoh()).isEqualTo(5); // unchanged
    }
}
