package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackRequest;
import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.entity.Feedback;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.FeedbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock FeedbackRepository feedbackRepository;
    @Mock NotificationService notificationService;
    @InjectMocks FeedbackService feedbackService;

    private User user(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    @Test
    void submit_persistsFeedbackForUserAndReturnsResponse() {
        User user = user(1L, "fb@shop.com");
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            f.setId(99L);
            return f;
        });

        FeedbackResponse response = feedbackService.submit(user, new FeedbackRequest("Great store", 5));

        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(captor.capture());
        Feedback saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getMessage()).isEqualTo("Great store");
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getCreatedAt()).isNotNull();

        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.userEmail()).isEqualTo("fb@shop.com");
        assertThat(response.rating()).isEqualTo(5);
    }

    @Test
    void submit_pushesFeedbackNotification() {
        User user = user(1L, "fb@shop.com");
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            f.setId(42L);
            return f;
        });

        feedbackService.submit(user, new FeedbackRequest("Great store", 5));

        verify(notificationService).notifyNewFeedback(any(FeedbackResponse.class));
    }

    @Test
    void listAll_mapsRepositoryResultsToResponsesNewestFirst() {
        User user = user(1L, "fb@shop.com");
        Feedback a = feedback(1L, user, "newest", 4, Instant.now());
        Feedback b = feedback(2L, user, "older", 2, Instant.now().minusSeconds(60));
        when(feedbackRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a, b));

        List<FeedbackResponse> all = feedbackService.listAll();

        assertThat(all).extracting(FeedbackResponse::message).containsExactly("newest", "older");
        assertThat(all).extracting(FeedbackResponse::rating).containsExactly(4, 2);
    }

    private Feedback feedback(Long id, User user, String message, int rating, Instant createdAt) {
        Feedback f = new Feedback();
        f.setId(id);
        f.setUser(user);
        f.setMessage(message);
        f.setRating(rating);
        f.setCreatedAt(createdAt);
        return f;
    }
}
