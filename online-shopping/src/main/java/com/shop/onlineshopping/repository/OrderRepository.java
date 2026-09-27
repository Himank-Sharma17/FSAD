package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserOrderByOrderDateDesc(User user);
    Optional<Order> findByStripeSessionId(String stripeSessionId);
}
