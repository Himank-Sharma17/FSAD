package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackReportResponse;
import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.dto.SalesGroupBy;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.dto.SalesPeriod;
import com.shop.onlineshopping.dto.SalesReportResponse;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock SalesAnalysisService salesAnalysisService;
    @Mock OrderRepository orderRepository;
    @Mock FeedbackService feedbackService;
    @InjectMocks ReportService reportService;

    @Test
    void salesReport_sumsCategoryLinesAndCountsPaidOrders() {
        when(salesAnalysisService.sales(SalesPeriod.MONTH, SalesGroupBy.CATEGORY)).thenReturn(List.of(
                new SalesLineResponse("ELECTRONICS", 3L, new BigDecimal("2000.00")),
                new SalesLineResponse("ELECTRICAL", 4L, new BigDecimal("200.00"))));
        when(orderRepository.countByStatusAndOrderDateGreaterThanEqual(eq(OrderStatus.PAID), any(Instant.class)))
                .thenReturn(5L);

        SalesReportResponse report = reportService.salesReport(SalesPeriod.MONTH);

        assertThat(report.period()).isEqualTo(SalesPeriod.MONTH);
        assertThat(report.totalItemsSold()).isEqualTo(7L);
        assertThat(report.totalRevenue()).isEqualByComparingTo("2200.00");
        assertThat(report.totalPaidOrders()).isEqualTo(5L);
        assertThat(report.byCategory()).hasSize(2);
    }

    @Test
    void salesReport_emptyPeriod_returnsZeroTotals() {
        when(salesAnalysisService.sales(SalesPeriod.WEEK, SalesGroupBy.CATEGORY)).thenReturn(List.of());
        when(orderRepository.countByStatusAndOrderDateGreaterThanEqual(eq(OrderStatus.PAID), any(Instant.class)))
                .thenReturn(0L);

        SalesReportResponse report = reportService.salesReport(SalesPeriod.WEEK);

        assertThat(report.totalItemsSold()).isZero();
        assertThat(report.totalRevenue()).isEqualByComparingTo("0");
        assertThat(report.totalPaidOrders()).isZero();
        assertThat(report.byCategory()).isEmpty();
    }

    @Test
    void feedbackReport_computesAverageAndDistribution() {
        when(feedbackService.listAll()).thenReturn(List.of(
                new FeedbackResponse(1L, "a@shop.com", "great", 5, Instant.now()),
                new FeedbackResponse(2L, "b@shop.com", "ok", 3, Instant.now()),
                new FeedbackResponse(3L, "c@shop.com", "great", 5, Instant.now())));

        FeedbackReportResponse report = reportService.feedbackReport();

        assertThat(report.totalCount()).isEqualTo(3L);
        assertThat(report.averageRating()).isEqualTo((5 + 3 + 5) / 3.0);
        assertThat(report.ratingCounts()).containsEntry(5, 2L).containsEntry(3, 1L)
                .containsEntry(1, 0L).containsEntry(2, 0L).containsEntry(4, 0L);
        assertThat(report.items()).hasSize(3);
    }

    @Test
    void feedbackReport_empty_averageIsZeroNoDivideByZero() {
        when(feedbackService.listAll()).thenReturn(List.of());

        FeedbackReportResponse report = reportService.feedbackReport();

        assertThat(report.totalCount()).isZero();
        assertThat(report.averageRating()).isEqualTo(0.0);
        assertThat(report.ratingCounts()).containsOnlyKeys(1, 2, 3, 4, 5);
        assertThat(report.ratingCounts().values()).allMatch(v -> v == 0L);
        assertThat(report.items()).isEmpty();
    }
}
