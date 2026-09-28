package com.shop.onlineshopping.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PaymentPropertiesTest {

    @Autowired
    Environment env;

    @Test
    void stripeAndMailPropertiesResolve() {
        assertThat(env.getProperty("stripe.secret-key")).isNotBlank();
        assertThat(env.getProperty("stripe.webhook-secret")).isEqualTo("whsec_test_secret");
        assertThat(env.getProperty("stripe.success-url")).isNotBlank();
        assertThat(env.getProperty("stripe.cancel-url")).isNotBlank();
        assertThat(env.getProperty("spring.mail.host")).isNotBlank();
        assertThat(env.getProperty("app.mail.from")).isNotBlank();
    }

    @Test
    void stripeJavaIsOnTheClasspath() {
        assertThat(com.stripe.Stripe.class).isNotNull();
    }
}
