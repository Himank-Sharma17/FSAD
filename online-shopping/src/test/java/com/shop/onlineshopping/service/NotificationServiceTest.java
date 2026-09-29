package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.dto.LowStockEvent;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NotificationServiceTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final NotificationService service = new NotificationService(template, 5);

    private Order order() {
        User u = new User();
        u.setId(1L);
        u.setEmail("buyer@shop.com");
        Order o = new Order();
        o.setId(7L);
        o.setUser(u);
        o.setStatus(OrderStatus.PAID);
        o.setOrderDate(Instant.now());
        o.setTotalAmount(new BigDecimal("10.00"));
        return o;
    }

    private Product product(int qoh) {
        Product p = new Product();
        p.setId(3L);
        p.setName("Widget");
        p.setQoh(qoh);
        return p;
    }

    @Test
    void notifyNewOrder_sendsOrderResponseToOrdersTopic() {
        service.notifyNewOrder(order());
        verify(template).convertAndSend(eq("/topic/admin/orders"), any(Object.class));
    }

    @Test
    void notifyNewFeedback_sendsToFeedbackTopic() {
        FeedbackResponse fb = new FeedbackResponse(9L, "buyer@shop.com", "Nice", 5, Instant.now());
        service.notifyNewFeedback(fb);
        verify(template).convertAndSend("/topic/admin/feedback", fb);
    }

    @Test
    void notifyLowStockIfCrossed_boundaryAndAlreadyLow() {
        // crosses from 6 down to 5 (== threshold) -> fires
        service.notifyLowStockIfCrossed(product(5), 6);
        ArgumentCaptor<LowStockEvent> captor = ArgumentCaptor.forClass(LowStockEvent.class);
        verify(template).convertAndSend(eq("/topic/admin/low-stock"), captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(3L);
        assertThat(captor.getValue().qoh()).isEqualTo(5);

        // already low (previous 5, not > threshold) -> no additional send
        service.notifyLowStockIfCrossed(product(4), 5);
        verify(template).convertAndSend(eq("/topic/admin/low-stock"), any(LowStockEvent.class));
    }

    @Test
    void notifyLowStockIfCrossed_staysAboveThreshold_doesNotSend() {
        service.notifyLowStockIfCrossed(product(8), 10);
        verify(template, never()).convertAndSend(eq("/topic/admin/low-stock"), any(LowStockEvent.class));
    }

    @Test
    void notify_whenTemplateThrows_doesNotPropagate() {
        doThrow(new RuntimeException("broker down"))
                .when(template).convertAndSend(any(String.class), any(Object.class));
        assertThatCode(() -> service.notifyNewOrder(order())).doesNotThrowAnyException();
        assertThatCode(() -> service.notifyNewFeedback(
                new FeedbackResponse(1L, "a@b.com", "m", 3, Instant.now()))).doesNotThrowAnyException();
        assertThatCode(() -> service.notifyLowStockIfCrossed(product(1), 6)).doesNotThrowAnyException();
    }
}
