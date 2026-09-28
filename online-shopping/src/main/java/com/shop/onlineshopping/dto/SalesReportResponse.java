package com.shop.onlineshopping.dto;

import java.math.BigDecimal;
import java.util.List;

public record SalesReportResponse(
        SalesPeriod period,
        Long totalItemsSold,
        BigDecimal totalRevenue,
        Long totalPaidOrders,
        List<SalesLineResponse> byCategory
) {}
