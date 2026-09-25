package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hashed");
        u.setFullName("Test User");
        u.setRole(Role.CUSTOMER);
        return u;
    }

    @Test
    void findByEmail_returnsSavedUser() {
        userRepository.save(newUser("a@shop.com"));

        Optional<User> found = userRepository.findByEmail("a@shop.com");

        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void existsByEmail_trueOnlyForSavedEmail() {
        userRepository.save(newUser("b@shop.com"));

        assertThat(userRepository.existsByEmail("b@shop.com")).isTrue();
        assertThat(userRepository.existsByEmail("missing@shop.com")).isFalse();
    }
}
