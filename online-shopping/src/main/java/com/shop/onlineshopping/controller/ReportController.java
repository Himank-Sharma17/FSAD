package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.dto.FeedbackReportResponse;
import com.shop.onlineshopping.dto.SalesPeriod;
import com.shop.onlineshopping.dto.SalesReportResponse;
import com.shop.onlineshopping.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/sales")
    public SalesReportResponse salesReport(@RequestParam(defaultValue = "MONTH") SalesPeriod period) {
        return reportService.salesReport(period);
    }

    @GetMapping("/feedback")
    public FeedbackReportResponse feedbackReport() {
        return reportService.feedbackReport();
    }
}
