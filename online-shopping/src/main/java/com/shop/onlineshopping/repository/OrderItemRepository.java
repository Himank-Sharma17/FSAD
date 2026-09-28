package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.dto.CategorySalesRow;
import com.shop.onlineshopping.dto.ProductMovementResponse;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("""
            SELECT new com.shop.onlineshopping.dto.SalesLineResponse(
                       oi.product.name, SUM(oi.quantity), SUM(oi.quantity * oi.priceAtPurchase))
            FROM OrderItem oi
            WHERE oi.order.status = com.shop.onlineshopping.entity.OrderStatus.PAID
              AND oi.order.orderDate >= :since
            GROUP BY oi.product.id, oi.product.name
            ORDER BY SUM(oi.quantity * oi.priceAtPurchase) DESC
            """)
    List<SalesLineResponse> salesByProduct(@Param("since") Instant since);

    @Query("""
            SELECT new com.shop.onlineshopping.dto.CategorySalesRow(
                       oi.product.category, SUM(oi.quantity), SUM(oi.quantity * oi.priceAtPurchase))
            FROM OrderItem oi
            WHERE oi.order.status = com.shop.onlineshopping.entity.OrderStatus.PAID
              AND oi.order.orderDate >= :since
            GROUP BY oi.product.category
            ORDER BY SUM(oi.quantity * oi.priceAtPurchase) DESC
            """)
    List<CategorySalesRow> salesByCategory(@Param("since") Instant since);

    @Query("""
            SELECT new com.shop.onlineshopping.dto.ProductMovementResponse(
                       oi.product.id, oi.product.name, SUM(oi.quantity))
            FROM OrderItem oi
            WHERE oi.order.status = com.shop.onlineshopping.entity.OrderStatus.PAID
              AND oi.order.orderDate >= :since
            GROUP BY oi.product.id, oi.product.name
            ORDER BY SUM(oi.quantity) DESC
            """)
    List<ProductMovementResponse> movementRanking(@Param("since") Instant since);
}
