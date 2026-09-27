package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class OrderRepositoryTest {

    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;

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

    private Order newOrder(User user, Product product, OrderStatus status, String sessionId, Instant date) {
        Order order = new Order();
        order.setUser(user);
        order.setStatus(status);
        order.setStripeSessionId(sessionId);
        order.setOrderDate(date);
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
    void findByUserOrderByOrderDateDesc_returnsNewestFirst() {
        User user = newUser("orders@shop.com");
        Product product = newProduct();
        newOrder(user, product, OrderStatus.PAID, "cs_old", Instant.now().minusSeconds(3600));
        newOrder(user, product, OrderStatus.PENDING_PAYMENT, "cs_new", Instant.now());

        List<Order> orders = orderRepository.findByUserOrderByOrderDateDesc(user);

        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getStripeSessionId()).isEqualTo("cs_new");
        assertThat(orders.get(1).getStripeSessionId()).isEqualTo("cs_old");
    }

    @Test
    void findByStripeSessionId_returnsMatchingOrderWithItems() {
        User user = newUser("session@shop.com");
        Product product = newProduct();
        newOrder(user, product, OrderStatus.PENDING_PAYMENT, "cs_lookup", Instant.now());

        Optional<Order> found = orderRepository.findByStripeSessionId("cs_lookup");

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(found.get().getItems()).hasSize(1);
        assertThat(found.get().getItems().get(0).getPriceAtPurchase()).isEqualByComparingTo("999.99");
    }
}
