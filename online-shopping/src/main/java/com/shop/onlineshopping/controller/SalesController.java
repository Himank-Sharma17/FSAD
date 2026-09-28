package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.dto.ProductMovementResponse;
import com.shop.onlineshopping.dto.SalesGroupBy;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.dto.SalesPeriod;
import com.shop.onlineshopping.service.SalesAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/sales")
public class SalesController {

    private final SalesAnalysisService salesAnalysisService;

    public SalesController(SalesAnalysisService salesAnalysisService) {
        this.salesAnalysisService = salesAnalysisService;
    }

    @GetMapping
    public List<SalesLineResponse> sales(
            @RequestParam(defaultValue = "MONTH") SalesPeriod period,
            @RequestParam(defaultValue = "PRODUCT") SalesGroupBy groupBy) {
        return salesAnalysisService.sales(period, groupBy);
    }

    @GetMapping("/movement")
    public List<ProductMovementResponse> movement(
            @RequestParam(defaultValue = "MONTH") SalesPeriod period) {
        return salesAnalysisService.movement(period);
    }
}
