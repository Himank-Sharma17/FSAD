package com.shop.onlineshopping.service;

import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.exception.ResourceNotFoundException;
import com.shop.onlineshopping.exception.UnauthorizedException;
import com.shop.onlineshopping.repository.OrderRepository;
import com.shop.onlineshopping.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductRepository productRepository;
    @Mock CartService cartService;
    @Mock EmailService emailService;
    @Mock NotificationService notificationService;
    @InjectMocks OrderService orderService;

    private User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setEmail("buyer@shop.com");
        return u;
    }

    private Order pendingOrder(User user, int productQoh, int quantity) {
        Product product = new Product();
        product.setId(5L);
        product.setName("Laptop");
        product.setPrice(new BigDecimal("999.99"));
        product.setQoh(productQoh);

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setPriceAtPurchase(product.getPrice());

        Order order = new Order();
        order.setId(1L);
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setStripeSessionId("cs_test_123");
        order.setTotalAmount(new BigDecimal("999.99"));
        order.getItems().add(item);
        return order;
    }

    @Test
    void markOrderPaid_pending_decrementsStockSetsPaidClearsCartAndEmails() {
        User user = user(1L);
        Order order = pendingOrder(user, 5, 2);
        when(orderRepository.findByStripeSessionId("cs_test_123")).thenReturn(Optional.of(order));

        orderService.markOrderPaid("cs_test_123");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getItems().get(0).getProduct().getQoh()).isEqualTo(3);
        verify(productRepository).save(order.getItems().get(0).getProduct());
        verify(orderRepository).save(order);
        verify(cartService).clearCart(user);
        verify(emailService).sendOrderConfirmation(order);
    }

    @Test
    void markOrderPaid_alreadyPaid_isIdempotent() {
        Order order = pendingOrder(user(1L), 5, 2);
        order.setStatus(OrderStatus.PAID);
        when(orderRepository.findByStripeSessionId("cs_test_123")).thenReturn(Optional.of(order));

        orderService.markOrderPaid("cs_test_123");

        assertThat(order.getItems().get(0).getProduct().getQoh()).isEqualTo(5);
        verify(productRepository, never()).save(any());
        verify(emailService, never()).sendOrderConfirmation(any());
    }

    @Test
    void markOrderPaid_unknownSession_returnsWithoutError() {
        when(orderRepository.findByStripeSessionId("cs_unknown")).thenReturn(Optional.empty());

        orderService.markOrderPaid("cs_unknown");

        verify(productRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void markOrderPaid_wouldGoNegative_clampsToZero() {
        Order order = pendingOrder(user(1L), 1, 5);
        when(orderRepository.findByStripeSessionId("cs_test_123")).thenReturn(Optional.of(order));

        orderService.markOrderPaid("cs_test_123");

        assertThat(order.getItems().get(0).getProduct().getQoh()).isZero();
    }

    @Test
    void deleteOrder_notOwner_throwsUnauthorized() {
        Order order = pendingOrder(user(1L), 5, 1);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.deleteOrder(user(2L), 1L))
                .isInstanceOf(UnauthorizedException.class);
        verify(orderRepository, never()).delete(any());
    }

    @Test
    void deleteOrder_unknown_throwsResourceNotFound() {
        when(orderRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orderService.deleteOrder(user(1L), 404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteOrder_owner_deletes() {
        User user = user(1L);
        Order order = pendingOrder(user, 5, 1);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.deleteOrder(user, 1L);

        verify(orderRepository).delete(order);
    }

    @Test
    void markOrderPaid_pushesNewOrderNotification() {
        User user = user(1L);
        Order order = pendingOrder(user, 5, 2);
        when(orderRepository.findByStripeSessionId("cs_test_123")).thenReturn(Optional.of(order));

        orderService.markOrderPaid("cs_test_123");

        verify(notificationService).notifyNewOrder(order);
    }

    @Test
    void markOrderPaid_pushesLowStockWithPreviousQoh() {
        User user = user(1L);
        Order order = pendingOrder(user, 6, 2); // qoh 6 -> 4, previous 6
        when(orderRepository.findByStripeSessionId("cs_test_123")).thenReturn(Optional.of(order));

        orderService.markOrderPaid("cs_test_123");

        // previousQoh passed is the value before decrement (6); NotificationService decides crossing
        verify(notificationService).notifyLowStockIfCrossed(order.getItems().get(0).getProduct(), 6);
    }
}
