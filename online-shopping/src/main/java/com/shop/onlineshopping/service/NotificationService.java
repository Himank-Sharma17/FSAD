package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.dto.LowStockEvent;
import com.shop.onlineshopping.dto.OrderResponse;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    static final String ORDERS = "/topic/admin/orders";
    static final String LOW_STOCK = "/topic/admin/low-stock";
    static final String FEEDBACK = "/topic/admin/feedback";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final int lowStockThreshold;

    public NotificationService(SimpMessagingTemplate messagingTemplate,
                               @Value("${app.low-stock-threshold}") int lowStockThreshold) {
        this.messagingTemplate = messagingTemplate;
        this.lowStockThreshold = lowStockThreshold;
    }

    public void notifyNewOrder(Order order) {
        send(ORDERS, OrderResponse.from(order));
    }

    public void notifyNewFeedback(FeedbackResponse feedback) {
        send(FEEDBACK, feedback);
    }

    public void notifyLowStockIfCrossed(Product product, int previousQoh) {
        if (previousQoh > lowStockThreshold && product.getQoh() <= lowStockThreshold) {
            send(LOW_STOCK, new LowStockEvent(product.getId(), product.getName(), product.getQoh()));
        }
    }

    private void send(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (RuntimeException e) {
            // Never let a notification failure break the caller's transaction (e.g. a paid order).
            log.warn("Failed to push notification to {}: {}", destination, e.getMessage());
        }
    }
}
