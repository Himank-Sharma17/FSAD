package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Autowired
    ProductRepository productRepository;

    private Product newProduct(String name, Category category) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal("9.99"));
        p.setQoh(10);
        p.setCategory(category);
        return p;
    }

    @Test
    void findByCategory_returnsOnlyMatchingCategory() {
        productRepository.save(newProduct("Laptop", Category.ELECTRONICS));
        productRepository.save(newProduct("Sofa", Category.FURNITURE));

        Page<Product> electronics = productRepository.findByCategory(
                Category.ELECTRONICS, PageRequest.of(0, 10));

        assertThat(electronics.getTotalElements()).isEqualTo(1);
        assertThat(electronics.getContent().get(0).getName()).isEqualTo("Laptop");
        assertThat(electronics.getContent().get(0).getCategory()).isEqualTo(Category.ELECTRONICS);
    }

    @Test
    void findAll_paginates() {
        for (int i = 0; i < 5; i++) {
            productRepository.save(newProduct("P" + i, Category.TOYS_AND_BOOKS));
        }

        Page<Product> firstPage = productRepository.findAll(PageRequest.of(0, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
    }

    @Test
    void savedProduct_persistsPriceAndQoh() {
        Product saved = productRepository.save(newProduct("Lipstick", Category.COSMETICS));

        Product found = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getPrice()).isEqualByComparingTo("9.99");
        assertThat(found.getQoh()).isEqualTo(10);
    }

    @Test
    void version_isAssignedOnSaveAndIncrementsOnUpdate() {
        Product saved = productRepository.saveAndFlush(newProduct("Laptop", Category.ELECTRONICS));
        assertThat(saved.getVersion()).isEqualTo(0L);

        saved.setQoh(3);
        Product updated = productRepository.saveAndFlush(saved);
        assertThat(updated.getVersion()).isEqualTo(1L);
    }
}
