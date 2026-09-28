package com.shop.onlineshopping.dto;

import java.util.List;
import java.util.Map;

public record FeedbackReportResponse(
        Long totalCount,
        Double averageRating,
        Map<Integer, Long> ratingCounts,
        List<FeedbackResponse> items
) {}
