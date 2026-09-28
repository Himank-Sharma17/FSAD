package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.OrderResponse;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.exception.ResourceNotFoundException;
import com.shop.onlineshopping.exception.UnauthorizedException;
import com.shop.onlineshopping.repository.OrderRepository;
import com.shop.onlineshopping.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CartService cartService;
    private final EmailService emailService;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        CartService cartService,
                        EmailService emailService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.cartService = cartService;
        this.emailService = emailService;
    }

    @Transactional
    public void markOrderPaid(String sessionId) {
        Optional<Order> maybeOrder = orderRepository.findByStripeSessionId(sessionId);
        if (maybeOrder.isEmpty()) {
            log.warn("Received webhook for unknown Stripe session {}; ignoring", sessionId);
            return;
        }
        Order order = maybeOrder.get();
        if (order.getStatus() == OrderStatus.PAID) {
            log.info("Order {} is already PAID; skipping (idempotent webhook)", order.getId());
            return;
        }

        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            int newQoh = product.getQoh() - item.getQuantity();
            if (newQoh < 0) {
                log.warn("QOH for product {} would go negative ({} - {}); clamping to 0",
                        product.getId(), product.getQoh(), item.getQuantity());
                newQoh = 0;
            }
            product.setQoh(newQoh);
            productRepository.save(product);
        }

        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);
        cartService.clearCart(order.getUser());
        emailService.sendOrderConfirmation(order);
        log.info("Order {} marked PAID", order.getId());
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(User user) {
        return orderRepository.findByUserOrderByOrderDateDesc(user).stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional
    public void deleteOrder(User user, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!order.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("Order does not belong to the current user");
        }
        orderRepository.delete(order);
    }
}
