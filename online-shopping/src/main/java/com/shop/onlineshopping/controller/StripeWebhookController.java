package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.service.OrderService;
import com.shop.onlineshopping.service.StripeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeGateway stripeGateway;
    private final OrderService orderService;

    public StripeWebhookController(StripeGateway stripeGateway, OrderService orderService) {
        this.stripeGateway = stripeGateway;
        this.orderService = orderService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handle(@RequestBody String payload,
                                         @RequestHeader("Stripe-Signature") String signature) {
        // Throws WebhookVerificationException (→ 400) on an invalid signature, before any state changes.
        String sessionId = stripeGateway.completedSessionId(payload, signature);
        log.info("Verified Stripe webhook for session {}", sessionId);
        orderService.markOrderPaid(sessionId);
        return ResponseEntity.ok("ok");
    }
}
