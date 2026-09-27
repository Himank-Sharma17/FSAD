package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.dto.ProductResponse;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductResponse> listProducts(
            @RequestParam(required = false) Category category,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return productService.listProducts(category, pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable Long id) {
        return productService.getProduct(id);
    }
}
