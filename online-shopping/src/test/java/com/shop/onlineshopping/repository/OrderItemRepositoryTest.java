package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.dto.CategorySalesRow;
import com.shop.onlineshopping.dto.ProductMovementResponse;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class OrderItemRepositoryTest {

    @Autowired OrderItemRepository orderItemRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired UserRepository userRepository;
    @Autowired TestEntityManager em;

    private User user;

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Product newProduct(String name, Category category, String price) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal(price));
        p.setQoh(100);
        p.setCategory(category);
        return productRepository.save(p);
    }

    private void newOrder(OrderStatus status, Instant date, Product product, int qty, String priceAtPurchase) {
        Order order = new Order();
        order.setUser(user);
        order.setStatus(status);
        order.setStripeSessionId("cs_" + System.nanoTime());
        order.setOrderDate(date);
        order.setTotalAmount(new BigDecimal(priceAtPurchase).multiply(BigDecimal.valueOf(qty)));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(qty);
        item.setPriceAtPurchase(new BigDecimal(priceAtPurchase));
        order.getItems().add(item);
        orderRepository.save(order);
    }

    @Test
    void salesByProduct_sumsOnlyPaidOrdersWithinPeriod() {
        user = newUser("sales@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Product lamp = newProduct("Lamp", Category.ELECTRICAL, "50.00");
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);

        newOrder(OrderStatus.PAID, recent, laptop, 2, "1000.00");   // counts: qty 2, rev 2000
        newOrder(OrderStatus.PAID, recent, lamp, 3, "50.00");       // counts: qty 3, rev 150
        newOrder(OrderStatus.PENDING_PAYMENT, recent, laptop, 5, "1000.00"); // excluded: not PAID
        newOrder(OrderStatus.PAID, old, laptop, 9, "1000.00");      // excluded: out of period

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<SalesLineResponse> lines = orderItemRepository.salesByProduct(since);

        assertThat(lines).hasSize(2);
        SalesLineResponse laptopLine = lines.stream()
                .filter(l -> l.label().equals("Laptop")).findFirst().orElseThrow();
        assertThat(laptopLine.totalQuantity()).isEqualTo(2L);
        assertThat(laptopLine.totalRevenue()).isEqualByComparingTo("2000.00");
        SalesLineResponse lampLine = lines.stream()
                .filter(l -> l.label().equals("Lamp")).findFirst().orElseThrow();
        assertThat(lampLine.totalQuantity()).isEqualTo(3L);
        assertThat(lampLine.totalRevenue()).isEqualByComparingTo("150.00");
    }

    @Test
    void salesByCategory_groupsPaidRevenueByCategory() {
        user = newUser("cat@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Product phone = newProduct("Phone", Category.ELECTRONICS, "500.00");
        Product lamp = newProduct("Lamp", Category.ELECTRICAL, "50.00");
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);

        newOrder(OrderStatus.PAID, recent, laptop, 1, "1000.00");
        newOrder(OrderStatus.PAID, recent, phone, 2, "500.00");   // ELECTRONICS total: qty 3, rev 2000
        newOrder(OrderStatus.PAID, recent, lamp, 4, "50.00");     // ELECTRICAL total: qty 4, rev 200

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<CategorySalesRow> rows = orderItemRepository.salesByCategory(since);

        assertThat(rows).hasSize(2);
        CategorySalesRow electronics = rows.stream()
                .filter(r -> r.category() == Category.ELECTRONICS).findFirst().orElseThrow();
        assertThat(electronics.totalQuantity()).isEqualTo(3L);
        assertThat(electronics.totalRevenue()).isEqualByComparingTo("2000.00");
        CategorySalesRow electrical = rows.stream()
                .filter(r -> r.category() == Category.ELECTRICAL).findFirst().orElseThrow();
        assertThat(electrical.totalQuantity()).isEqualTo(4L);
        assertThat(electrical.totalRevenue()).isEqualByComparingTo("200.00");
    }

    @Test
    void movementRanking_ordersByQuantitySoldDescending() {
        user = newUser("move@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Product lamp = newProduct("Lamp", Category.ELECTRICAL, "50.00");
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);

        newOrder(OrderStatus.PAID, recent, laptop, 2, "1000.00");
        newOrder(OrderStatus.PAID, recent, lamp, 10, "50.00");

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<ProductMovementResponse> ranking = orderItemRepository.movementRanking(since);

        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).productName()).isEqualTo("Lamp");
        assertThat(ranking.get(0).totalQuantitySold()).isEqualTo(10L);
        assertThat(ranking.get(1).productName()).isEqualTo("Laptop");
        assertThat(ranking.get(1).totalQuantitySold()).isEqualTo(2L);
    }

    @Test
    void salesByProduct_noPaidOrdersInPeriod_returnsEmpty() {
        user = newUser("empty@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        newOrder(OrderStatus.PENDING_PAYMENT, Instant.now(), laptop, 1, "1000.00");

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(orderItemRepository.salesByProduct(since)).isEmpty();
    }

    @Test
    void countByStatusAndOrderDateGreaterThanEqual_countsOnlyPaidInPeriod() {
        user = newUser("count@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);

        newOrder(OrderStatus.PAID, recent, laptop, 1, "1000.00");
        newOrder(OrderStatus.PAID, recent, laptop, 1, "1000.00");
        newOrder(OrderStatus.PENDING_PAYMENT, recent, laptop, 1, "1000.00"); // not PAID
        newOrder(OrderStatus.PAID, old, laptop, 1, "1000.00");               // out of period

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        long count = orderRepository.countByStatusAndOrderDateGreaterThanEqual(OrderStatus.PAID, since);
        assertThat(count).isEqualTo(2L);
    }
}
