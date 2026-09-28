package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Feedback;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class FeedbackRepositoryTest {

    @Autowired FeedbackRepository feedbackRepository;
    @Autowired UserRepository userRepository;
    @Autowired TestEntityManager em;

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Feedback newFeedback(User user, String message, int rating, Instant createdAt) {
        Feedback f = new Feedback();
        f.setUser(user);
        f.setMessage(message);
        f.setRating(rating);
        f.setCreatedAt(createdAt);
        return feedbackRepository.save(f);
    }

    @Test
    void findAllByOrderByCreatedAtDesc_returnsNewestFirst() {
        User user = newUser("fb@shop.com");
        newFeedback(user, "old", 3, Instant.now().minusSeconds(3600));
        newFeedback(user, "new", 5, Instant.now());

        em.flush();
        em.clear();

        List<Feedback> all = feedbackRepository.findAllByOrderByCreatedAtDesc();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getMessage()).isEqualTo("new");
        assertThat(all.get(1).getMessage()).isEqualTo("old");
        assertThat(all.get(0).getUser().getEmail()).isEqualTo("fb@shop.com");
    }
}
