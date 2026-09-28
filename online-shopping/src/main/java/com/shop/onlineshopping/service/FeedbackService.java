package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackRequest;
import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.entity.Feedback;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final FeedbackRepository feedbackRepository;

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @Transactional
    public FeedbackResponse submit(User user, FeedbackRequest request) {
        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setMessage(request.message());
        feedback.setRating(request.rating());
        Feedback saved = feedbackRepository.save(feedback);
        log.info("Feedback {} submitted by user {}", saved.getId(), user.getId());
        return FeedbackResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> listAll() {
        return feedbackRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(FeedbackResponse::from)
                .toList();
    }
}
