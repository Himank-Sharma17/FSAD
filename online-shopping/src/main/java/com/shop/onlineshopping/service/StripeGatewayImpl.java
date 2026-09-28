package com.shop.onlineshopping.service;

import com.shop.onlineshopping.exception.WebhookVerificationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StripeGatewayImpl implements StripeGateway {

    private final String webhookSecret;
    private final String successUrl;
    private final String cancelUrl;

    public StripeGatewayImpl(@Value("${stripe.webhook-secret}") String webhookSecret,
                             @Value("${stripe.success-url}") String successUrl,
                             @Value("${stripe.cancel-url}") String cancelUrl) {
        this.webhookSecret = webhookSecret;
        this.successUrl = successUrl;
        this.cancelUrl = cancelUrl;
    }

    @Override
    public StripeSession createCheckoutSession(CheckoutRequest request) {
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setCustomerEmail(request.customerEmail());

        for (LineItemData line : request.items()) {
            long unitAmount = line.unitPrice().movePointRight(2).longValueExact();
            builder.addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity((long) line.quantity())
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("usd")
                            .setUnitAmount(unitAmount)
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(line.name())
                                    .build())
                            .build())
                    .build());
        }

        try {
            Session session = Session.create(builder.build());
            return new StripeSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create Stripe checkout session", e);
        }
    }

    @Override
    public String completedSessionId(String payload, String signature) {
        try {
            Event event = Webhook.constructEvent(payload, signature, webhookSecret);
            EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
            // getObject() is empty when the event's API version differs from the SDK's;
            // fall back to deserializeUnsafe() to still read the session id.
            StripeObject stripeObject = deserializer.getObject()
                    .orElseGet(() -> {
                        try {
                            return deserializer.deserializeUnsafe();
                        } catch (com.stripe.exception.EventDataObjectDeserializationException ex) {
                            throw new WebhookVerificationException("Failed to deserialize webhook event");
                        }
                    });
            if (stripeObject instanceof Session session) {
                return session.getId();
            }
            throw new WebhookVerificationException("Unexpected webhook event object type");
        } catch (SignatureVerificationException e) {
            throw new WebhookVerificationException("Invalid Stripe webhook signature");
        }
    }
}
