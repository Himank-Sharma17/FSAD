package com.shop.onlineshopping.service;

import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public EmailService(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendOrderConfirmation(Order order) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(order.getUser().getEmail());
            message.setSubject("Order confirmation #" + order.getId());
            message.setText(buildBody(order));
            mailSender.send(message);
            log.info("Order confirmation email sent for order {}", order.getId());
        } catch (MailException e) {
            // Never let a mail failure break the payment webhook — log and move on.
            log.error("Failed to send order confirmation email for order {}: {}", order.getId(), e.getMessage());
        }
    }

    private String buildBody(Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thank you for your order #").append(order.getId()).append("!\n\n");
        sb.append("Items:\n");
        for (OrderItem item : order.getItems()) {
            sb.append("  - ")
                    .append(item.getProduct().getName())
                    .append(" x ").append(item.getQuantity())
                    .append(" @ ").append(item.getPriceAtPurchase())
                    .append("\n");
        }
        sb.append("\nTotal: ").append(order.getTotalAmount()).append("\n");
        return sb.toString();
    }
}
