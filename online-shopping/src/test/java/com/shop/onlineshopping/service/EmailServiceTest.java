package com.shop.onlineshopping.service;

import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock JavaMailSender mailSender;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender, "no-reply@shop.com");
    }

    private Order sampleOrder() {
        User user = new User();
        user.setEmail("buyer@shop.com");

        Product product = new Product();
        product.setName("Laptop");
        product.setPrice(new BigDecimal("999.99"));

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(1);
        item.setPriceAtPurchase(new BigDecimal("999.99"));

        Order order = new Order();
        order.setId(42L);
        order.setUser(user);
        order.setTotalAmount(new BigDecimal("999.99"));
        order.getItems().add(item);
        return order;
    }

    @Test
    void sendOrderConfirmation_sendsMessageToCustomer() {
        emailService.sendOrderConfirmation(sampleOrder());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("buyer@shop.com");
        assertThat(sent.getFrom()).isEqualTo("no-reply@shop.com");
        assertThat(sent.getSubject()).contains("42");
        assertThat(sent.getText()).contains("Laptop");
    }

    @Test
    void sendOrderConfirmation_swallowsMailException() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        assertThatCode(() -> emailService.sendOrderConfirmation(sampleOrder()))
                .doesNotThrowAnyException();
    }
}
