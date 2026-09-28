package com.shop.onlineshopping.service;

import java.math.BigDecimal;
import java.util.List;

public interface StripeGateway {

    record LineItemData(String name, BigDecimal unitPrice, int quantity) {}

    record CheckoutRequest(String customerEmail, List<LineItemData> items) {}

    record StripeSession(String id, String url) {}

    /** Creates a Stripe hosted-checkout session and returns its id + redirect URL. */
    StripeSession createCheckoutSession(CheckoutRequest request);

    /**
     * Verifies the webhook signature and returns the completed checkout Session id.
     * @throws com.shop.onlineshopping.exception.WebhookVerificationException on invalid signature.
     */
    String completedSessionId(String payload, String signature);
}
