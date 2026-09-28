package com.shop.onlineshopping.dto;

import com.shop.onlineshopping.entity.Feedback;

import java.time.Instant;

public record FeedbackResponse(
        Long id,
        String userEmail,
        String message,
        int rating,
        Instant createdAt
) {
    public static FeedbackResponse from(Feedback f) {
        return new FeedbackResponse(
                f.getId(),
                f.getUser().getEmail(),
                f.getMessage(),
                f.getRating(),
                f.getCreatedAt());
    }
}
