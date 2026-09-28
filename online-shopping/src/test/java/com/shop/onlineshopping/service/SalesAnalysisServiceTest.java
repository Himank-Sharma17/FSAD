package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.CategorySalesRow;
import com.shop.onlineshopping.dto.ProductMovementResponse;
import com.shop.onlineshopping.dto.SalesGroupBy;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.dto.SalesPeriod;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.repository.OrderItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesAnalysisServiceTest {

    @Mock OrderItemRepository orderItemRepository;
    @InjectMocks SalesAnalysisService salesAnalysisService;

    @Test
    void sales_byProduct_delegatesToSalesByProduct() {
        List<SalesLineResponse> expected = List.of(new SalesLineResponse("Laptop", 2L, new BigDecimal("2000.00")));
        when(orderItemRepository.salesByProduct(any(Instant.class))).thenReturn(expected);

        List<SalesLineResponse> result = salesAnalysisService.sales(SalesPeriod.MONTH, SalesGroupBy.PRODUCT);

        assertThat(result).isEqualTo(expected);
        verify(orderItemRepository).salesByProduct(any(Instant.class));
    }

    @Test
    void sales_byCategory_mapsCategoryRowsToLinesWithEnumNameLabel() {
        when(orderItemRepository.salesByCategory(any(Instant.class))).thenReturn(List.of(
                new CategorySalesRow(Category.ELECTRONICS, 3L, new BigDecimal("2000.00")),
                new CategorySalesRow(Category.ELECTRICAL, 4L, new BigDecimal("200.00"))));

        List<SalesLineResponse> result = salesAnalysisService.sales(SalesPeriod.YEAR, SalesGroupBy.CATEGORY);

        assertThat(result).containsExactly(
                new SalesLineResponse("ELECTRONICS", 3L, new BigDecimal("2000.00")),
                new SalesLineResponse("ELECTRICAL", 4L, new BigDecimal("200.00")));
    }

    @Test
    void movement_delegatesToMovementRanking() {
        List<ProductMovementResponse> expected = List.of(new ProductMovementResponse(1L, "Lamp", 10L));
        when(orderItemRepository.movementRanking(any(Instant.class))).thenReturn(expected);

        assertThat(salesAnalysisService.movement(SalesPeriod.WEEK)).isEqualTo(expected);
    }

    @Test
    void sales_weekPeriod_passesSinceRoughlyOneWeekAgo() {
        when(orderItemRepository.salesByProduct(any(Instant.class))).thenReturn(List.of());

        salesAnalysisService.sales(SalesPeriod.WEEK, SalesGroupBy.PRODUCT);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(orderItemRepository).salesByProduct(captor.capture());
        Instant since = captor.getValue();
        Instant expected = Instant.now().minus(7, ChronoUnit.DAYS);
        // Allow a wide tolerance so the test is not clock-flaky.
        assertThat(since).isBetween(expected.minus(1, ChronoUnit.DAYS), expected.plus(1, ChronoUnit.DAYS));
    }

    @Test
    void since_isNonNullForEveryPeriod() {
        for (SalesPeriod p : SalesPeriod.values()) {
            assertThat(p.since()).isNotNull().isBefore(Instant.now());
        }
        verifyNoInteractions(orderItemRepository);
    }
}
