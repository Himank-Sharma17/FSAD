package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.ProductRequest;
import com.shop.onlineshopping.dto.ProductResponse;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.exception.ResourceNotFoundException;
import com.shop.onlineshopping.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock ProductRepository productRepository;
    @InjectMocks ProductService productService;

    private Product sample(Long id) {
        Product p = new Product();
        p.setId(id);
        p.setName("Laptop");
        p.setDescription("A laptop");
        p.setPrice(new BigDecimal("999.99"));
        p.setQoh(5);
        p.setCategory(Category.ELECTRONICS);
        return p;
    }

    private ProductRequest request() {
        return new ProductRequest("Laptop", "A laptop", new BigDecimal("999.99"), 5, Category.ELECTRONICS);
    }

    @Test
    void listProducts_noCategory_usesFindAll() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(sample(1L))));

        Page<ProductResponse> page = productService.listProducts(null, pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).name()).isEqualTo("Laptop");
        verify(productRepository).findAll(pageable);
        verify(productRepository, never()).findByCategory(any(), any());
    }

    @Test
    void listProducts_withCategory_usesFindByCategory() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findByCategory(Category.ELECTRONICS, pageable))
                .thenReturn(new PageImpl<>(List.of(sample(1L))));

        Page<ProductResponse> page = productService.listProducts(Category.ELECTRONICS, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(productRepository).findByCategory(Category.ELECTRONICS, pageable);
        verify(productRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void getProduct_missing_throwsNotFound() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProduct(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getProduct_existing_returnsMappedResponse() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(sample(1L)));

        ProductResponse res = productService.getProduct(1L);

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.name()).isEqualTo("Laptop");
        assertThat(res.price()).isEqualByComparingTo("999.99");
    }

    @Test
    void createProduct_savesAndReturnsMappedResponse() {
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        ProductResponse res = productService.createProduct(request());

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Laptop");
        assertThat(saved.getValue().getQoh()).isEqualTo(5);
        assertThat(saved.getValue().getCategory()).isEqualTo(Category.ELECTRONICS);
        assertThat(res.id()).isEqualTo(1L);
    }

    @Test
    void updateProduct_missing_throwsNotFound() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateProduct(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(productRepository, never()).save(any());
    }

    @Test
    void updateProduct_existing_appliesChanges() {
        Product existing = sample(1L);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductResponse res = productService.updateProduct(1L,
                new ProductRequest("Gaming Laptop", "Fast", new BigDecimal("1299.00"), 3, Category.ELECTRONICS));

        assertThat(res.name()).isEqualTo("Gaming Laptop");
        assertThat(res.price()).isEqualByComparingTo("1299.00");
        assertThat(res.qoh()).isEqualTo(3);
    }

    @Test
    void deleteProduct_missing_throwsNotFound() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.deleteProduct(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(productRepository, never()).delete(any());
    }

    @Test
    void deleteProduct_existing_deletes() {
        Product existing = sample(1L);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        productService.deleteProduct(1L);

        verify(productRepository).delete(existing);
    }
}
