package com.shop.onlineshopping.service;

import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("test-secret-key-that-is-at-least-32-bytes-long!!", 3_600_000L);

    private User customer() {
        User u = new User();
        u.setEmail("c@shop.com");
        u.setRole(Role.CUSTOMER);
        return u;
    }

    @Test
    void generatedToken_roundTripsEmailAndRole() {
        String token = jwtService.generateToken(customer());

        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractEmail(token)).isEqualTo("c@shop.com");
        assertThat(jwtService.extractRole(token)).isEqualTo("CUSTOMER");
    }

    @Test
    void garbageToken_isNotValid() {
        assertThat(jwtService.isValid("not-a-jwt")).isFalse();
    }

    @Test
    void expiredToken_isNotValid() throws InterruptedException {
        JwtService shortLived =
                new JwtService("test-secret-key-that-is-at-least-32-bytes-long!!", 1L);
        String token = shortLived.generateToken(customer());
        Thread.sleep(5);

        assertThat(shortLived.isValid(token)).isFalse();
    }
}
