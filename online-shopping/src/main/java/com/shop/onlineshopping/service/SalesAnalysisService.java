package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.ProductMovementResponse;
import com.shop.onlineshopping.dto.SalesGroupBy;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.dto.SalesPeriod;
import com.shop.onlineshopping.repository.OrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class SalesAnalysisService {

    private final OrderItemRepository orderItemRepository;

    public SalesAnalysisService(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }

    @Transactional(readOnly = true)
    public List<SalesLineResponse> sales(SalesPeriod period, SalesGroupBy groupBy) {
        Instant since = period.since();
        return switch (groupBy) {
            case PRODUCT -> orderItemRepository.salesByProduct(since);
            case CATEGORY -> orderItemRepository.salesByCategory(since).stream()
                    .map(row -> new SalesLineResponse(row.category().name(), row.totalQuantity(), row.totalRevenue()))
                    .toList();
        };
    }

    @Transactional(readOnly = true)
    public List<ProductMovementResponse> movement(SalesPeriod period) {
        return orderItemRepository.movementRanking(period.since());
    }
}
