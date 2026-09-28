package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackReportResponse;
import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.dto.SalesGroupBy;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.dto.SalesPeriod;
import com.shop.onlineshopping.dto.SalesReportResponse;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    private final SalesAnalysisService salesAnalysisService;
    private final OrderRepository orderRepository;
    private final FeedbackService feedbackService;

    public ReportService(SalesAnalysisService salesAnalysisService,
                         OrderRepository orderRepository,
                         FeedbackService feedbackService) {
        this.salesAnalysisService = salesAnalysisService;
        this.orderRepository = orderRepository;
        this.feedbackService = feedbackService;
    }

    @Transactional(readOnly = true)
    public SalesReportResponse salesReport(SalesPeriod period) {
        List<SalesLineResponse> byCategory = salesAnalysisService.sales(period, SalesGroupBy.CATEGORY);
        long totalItems = byCategory.stream().mapToLong(l -> l.totalQuantity()).sum();
        BigDecimal totalRevenue = byCategory.stream()
                .map(SalesLineResponse::totalRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalPaidOrders = orderRepository.countByStatusAndOrderDateGreaterThanEqual(
                OrderStatus.PAID, period.since());
        return new SalesReportResponse(period, totalItems, totalRevenue, totalPaidOrders, byCategory);
    }

    @Transactional(readOnly = true)
    public FeedbackReportResponse feedbackReport() {
        List<FeedbackResponse> items = feedbackService.listAll();
        long totalCount = items.size();
        double averageRating = items.isEmpty()
                ? 0.0
                : items.stream().mapToInt(FeedbackResponse::rating).average().orElse(0.0);
        Map<Integer, Long> ratingCounts = new LinkedHashMap<>();
        for (int r = 1; r <= 5; r++) {
            ratingCounts.put(r, 0L);
        }
        for (FeedbackResponse item : items) {
            ratingCounts.merge(item.rating(), 1L, Long::sum);
        }
        return new FeedbackReportResponse(totalCount, averageRating, ratingCounts, items);
    }
}
