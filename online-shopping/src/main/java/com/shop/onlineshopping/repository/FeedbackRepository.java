package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Feedback;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    @EntityGraph(attributePaths = "user")
    List<Feedback> findAllByOrderByCreatedAtDesc();
}
