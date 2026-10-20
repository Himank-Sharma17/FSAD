# Product Catalog — Implementation Plan (Phase 1, Plan 2 of 5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the product catalog — a `Product` entity over the 5 FSAD fields + fixed `Category` enum, customer-facing search (paginated list, category filter, single product), and admin CRUD (add/modify/delete) — on top of the auth + error-handling foundation from Plan 1.

**Architecture:** Classic layered Spring Boot REST (controller → service → repository → entity), same as Plan 1. Reads (`GET /api/products/**`) are open to any authenticated user; writes (`/api/admin/products/**`) are ADMIN-only. Both role gates are **already enforced** by the Plan 1 `SecurityConfig` (`/api/admin/**` → ADMIN, everything else authenticated) — this plan adds **no** security, pom, or property changes. DTOs cross the wire; the `Product` entity is never returned or accepted directly.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 · Spring Web MVC · Spring Data JPA/Hibernate · Spring Data Web (pagination) · Bean Validation · H2 (test) · JUnit 5 + Mockito + MockMvc.

**Spec:** [docs/superpowers/specs/2026-09-25-online-shopping-design.md](../specs/2026-09-25-online-shopping-design.md) — Modules 2 (Products Search) and 5 (Products Updation).

## Global Constraints

- Module root: `online-shopping/`. Base package: `com.shop.onlineshopping`. Classes live under `<base>/<layer>/` (`entity`, `repository`, `service`, `controller`, `dto`, `exception`). Tests mirror packages under `src/test/java/`.
- Java 21, Spring Boot 4.1.1, Maven. **Do NOT edit `pom.xml`, `application.properties`, `application-test.properties`, `SecurityConfig`, `JwtAuthFilter`, or any Plan 1 auth/config class.** Everything this plan needs is already on the classpath. If you believe an infra/config/security change is required, **STOP and report** rather than editing.
- **Category is a fixed Java enum** of exactly five constants, in this order: `ELECTRONICS, ELECTRICAL, FURNITURE, COSMETICS, TOYS_AND_BOOKS`. Never a table; never add/remove values.
- **Product fields (the 5 FSAD fields + category):** `id` (Long, IDENTITY), `name` (String, not null), `description` (String, nullable, ≤1000 chars), `price` (`java.math.BigDecimal`, not null, precision 12 scale 2), `qoh` (`int`, not null, ≥0), `category` (`Category`, `@Enumerated(EnumType.STRING)`, not null).
- **DTOs cross the wire; the `Product` entity is never returned or accepted directly.** `ProductResponse` is the only product shape returned; `ProductRequest` is the only product shape accepted.
- **Route access (already gated by Plan 1 `SecurityConfig`, no change needed):** `GET /api/products` and `GET /api/products/{id}` → any authenticated user (CUSTOMER or ADMIN). `POST/PUT/DELETE /api/admin/products/**` → ADMIN only. Anonymous → 4xx on both.
- **Money is `BigDecimal`**, never `double`/`float`. Compare with `isEqualByComparingTo` in tests.
- **Boot 4 verified test imports (empirically confirmed in Plan 1 — do NOT "fix" to Boot 3 paths):**
  - `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
  - `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
  - In MockMvc tests needing JSON, use `private final ObjectMapper objectMapper = new ObjectMapper();` (Jackson 2, self-constructed) — **never** `@Autowired ObjectMapper` (Boot 4 has no bean of the Jackson-2 type).
- Every test class is annotated `@ActiveProfiles("test")` (runs on in-memory H2).
- Commit after each task with the exact message given in its final step.

## Review Focus

- **Negative or zero price** on admin create/update → **400**, and nothing is persisted. (Task 5)
- **Negative QOH** on admin create/update → **400**. (Task 5)
- **Unknown category value** in `?category=NOPE` → **400**, not a 500 or stack trace. (Task 4, handler in Task 2)
- **GET/PUT/DELETE on a non-existent product id** → **404** JSON (`ApiError`), not a 500. (Tasks 4, 5)
- **Auth/role gating:** anonymous request to `/api/products` or `/api/admin/products` → **401/4xx**; a valid CUSTOMER token on `/api/admin/products/**` → **403**, never 200. (Tasks 4, 5)

---

## File Structure

```
online-shopping/src/main/java/com/shop/onlineshopping/
├── entity/Category.java                              (create)
├── entity/Product.java                               (create)
├── repository/ProductRepository.java                 (create)
├── exception/ResourceNotFoundException.java          (create)
├── exception/GlobalExceptionHandler.java             (modify: add 404 + type-mismatch 400 handlers)
├── dto/ProductRequest.java                           (create)
├── dto/ProductResponse.java                          (create)
├── service/ProductService.java                       (create)
├── controller/ProductController.java                 (create)   ── customer read (Module 2)
└── controller/AdminProductController.java            (create)   ── admin CRUD  (Module 5)

online-shopping/src/test/java/com/shop/onlineshopping/
├── repository/ProductRepositoryTest.java             (create)
├── service/ProductServiceTest.java                   (create)
├── controller/ProductControllerTest.java             (create)
└── controller/AdminProductControllerTest.java        (create)
```

---

### Task 1: Category enum, Product entity, ProductRepository

**Files:**
- Create: `.../entity/Category.java`, `.../entity/Product.java`, `.../repository/ProductRepository.java`
- Test: `.../repository/ProductRepositoryTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum Category { ELECTRONICS, ELECTRICAL, FURNITURE, COSMETICS, TOYS_AND_BOOKS }`
  - `Product` entity, Lombok `@Getter/@Setter`: `Long getId()`, `String getName()/setName`, `String getDescription()/setDescription`, `BigDecimal getPrice()/setPrice`, `int getQoh()/setQoh`, `Category getCategory()/setCategory`.
  - `ProductRepository extends JpaRepository<Product, Long>` with `Page<Product> findByCategory(Category category, Pageable pageable)` (plus inherited `findAll(Pageable)`).

- [ ] **Step 1: Write the failing repository test**

Create `src/test/java/com/shop/onlineshopping/repository/ProductRepositoryTest.java`:

```java
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
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=ProductRepositoryTest`
Expected: FAIL — `Category`, `Product`, `ProductRepository` do not exist (compilation error).

- [ ] **Step 3: Create `Category`**

`src/main/java/com/shop/onlineshopping/entity/Category.java`:

```java
package com.shop.onlineshopping.entity;

public enum Category {
    ELECTRONICS,
    ELECTRICAL,
    FURNITURE,
    COSMETICS,
    TOYS_AND_BOOKS
}
```

- [ ] **Step 4: Create `Product`**

`src/main/java/com/shop/onlineshopping/entity/Product.java`:

```java
package com.shop.onlineshopping.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@Getter
@Setter
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int qoh;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;
}
```

- [ ] **Step 5: Create `ProductRepository`**

`src/main/java/com/shop/onlineshopping/repository/ProductRepository.java`:

```java
package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByCategory(Category category, Pageable pageable);
}
```

- [ ] **Step 6: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=ProductRepositoryTest`
Expected: PASS (3 cases).

- [ ] **Step 7: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/entity/Category.java \
        online-shopping/src/main/java/com/shop/onlineshopping/entity/Product.java \
        online-shopping/src/main/java/com/shop/onlineshopping/repository/ProductRepository.java \
        online-shopping/src/test/java/com/shop/onlineshopping/repository/ProductRepositoryTest.java
git commit -m "feat: add Product entity, Category enum, and ProductRepository"
```

---

### Task 2: ResourceNotFoundException, error-handler mappings, product DTOs

**Files:**
- Create: `.../exception/ResourceNotFoundException.java`, `.../dto/ProductRequest.java`, `.../dto/ProductResponse.java`
- Modify: `.../exception/GlobalExceptionHandler.java` (add two handlers; leave existing handlers untouched)
- Test: none (exercised via Tasks 4 and 5 integration tests); build must compile.

**Interfaces:**
- Consumes: `Product`, `Category` (Task 1); existing `ApiError` DTO + `GlobalExceptionHandler` (Plan 1).
- Produces:
  - `ResourceNotFoundException extends RuntimeException` (single `String` message ctor) → mapped to **404**.
  - `record ProductRequest(String name, String description, BigDecimal price, Integer qoh, Category category)` — validated.
  - `record ProductResponse(Long id, String name, String description, BigDecimal price, Integer qoh, Category category)` with `static ProductResponse from(Product)`.
  - `GlobalExceptionHandler` additionally maps `ResourceNotFoundException` → 404 and `MethodArgumentTypeMismatchException` → 400.

- [ ] **Step 1: Create `ResourceNotFoundException`**

`src/main/java/com/shop/onlineshopping/exception/ResourceNotFoundException.java`:

```java
package com.shop.onlineshopping.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Create `ProductRequest`**

`src/main/java/com/shop/onlineshopping/dto/ProductRequest.java`:

```java
package com.shop.onlineshopping.dto;

import com.shop.onlineshopping.entity.Category;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank String name,
        @Size(max = 1000) String description,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal price,
        @NotNull @Min(0) Integer qoh,
        @NotNull Category category
) {}
```

- [ ] **Step 3: Create `ProductResponse`**

`src/main/java/com/shop/onlineshopping/dto/ProductResponse.java`:

```java
package com.shop.onlineshopping.dto;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer qoh,
        Category category
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getQoh(),
                product.getCategory());
    }
}
```

- [ ] **Step 4: Add two handlers to `GlobalExceptionHandler`**

Open `src/main/java/com/shop/onlineshopping/exception/GlobalExceptionHandler.java`. **Do not change the existing handlers.** Add these two imports alongside the current ones:

```java
import com.shop.onlineshopping.dto.ApiError; // already present — do not duplicate
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
```

Then add these two `@ExceptionHandler` methods inside the class (e.g. just after `handleValidation`):

```java
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        // e.g. ?category=NOPE cannot bind to the Category enum → 400, not a 500.
        String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'";
        return build(HttpStatus.BAD_REQUEST, message, req);
    }
```

(The private `build(...)` helper and the `HttpStatus`, `ResponseEntity`, `HttpServletRequest`, and `@ExceptionHandler` imports already exist from Plan 1.)

- [ ] **Step 5: Compile to verify it builds**

Run: `cd online-shopping && ./mvnw test-compile`
Expected: BUILD SUCCESS (no new test yet — these types are exercised in Tasks 4 and 5).

- [ ] **Step 6: Run the existing suite to confirm no regression**

Run: `cd online-shopping && ./mvnw test`
Expected: BUILD SUCCESS — all Plan 1 tests still green (the new handlers are additive).

- [ ] **Step 7: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/exception/ResourceNotFoundException.java \
        online-shopping/src/main/java/com/shop/onlineshopping/exception/GlobalExceptionHandler.java \
        online-shopping/src/main/java/com/shop/onlineshopping/dto/ProductRequest.java \
        online-shopping/src/main/java/com/shop/onlineshopping/dto/ProductResponse.java
git commit -m "feat: add product DTOs, ResourceNotFoundException, and 404/type-mismatch handlers"
```

---

### Task 3: ProductService

**Files:**
- Create: `.../service/ProductService.java`
- Test: `.../service/ProductServiceTest.java`

**Interfaces:**
- Consumes: `ProductRepository` (Task 1), `ProductRequest`/`ProductResponse` (Task 2), `ResourceNotFoundException` (Task 2), `Category`/`Product` (Task 1).
- Produces: `ProductService` with:
  - `Page<ProductResponse> listProducts(Category category, Pageable pageable)` — `category == null` → `findAll(pageable)`, else `findByCategory(category, pageable)`; maps each `Product` → `ProductResponse`.
  - `ProductResponse getProduct(Long id)` — 404 if absent.
  - `ProductResponse createProduct(ProductRequest req)`.
  - `ProductResponse updateProduct(Long id, ProductRequest req)` — 404 if absent.
  - `void deleteProduct(Long id)` — 404 if absent.

- [ ] **Step 1: Write the failing test** (unit test, Mockito — no Spring context)

Create `src/test/java/com/shop/onlineshopping/service/ProductServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=ProductServiceTest`
Expected: FAIL — `ProductService` does not exist.

- [ ] **Step 3: Implement `ProductService`**

`src/main/java/com/shop/onlineshopping/service/ProductService.java`:

```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.ProductRequest;
import com.shop.onlineshopping.dto.ProductResponse;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.exception.ResourceNotFoundException;
import com.shop.onlineshopping.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Page<ProductResponse> listProducts(Category category, Pageable pageable) {
        Page<Product> products = (category == null)
                ? productRepository.findAll(pageable)
                : productRepository.findByCategory(category, pageable);
        return products.map(ProductResponse::from);
    }

    public ProductResponse getProduct(Long id) {
        return ProductResponse.from(findOrThrow(id));
    }

    public ProductResponse createProduct(ProductRequest request) {
        Product product = new Product();
        apply(product, request);
        return ProductResponse.from(productRepository.save(product));
    }

    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = findOrThrow(id);
        apply(product, request);
        return ProductResponse.from(productRepository.save(product));
    }

    public void deleteProduct(Long id) {
        productRepository.delete(findOrThrow(id));
    }

    private Product findOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    private void apply(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setQoh(request.qoh());
        product.setCategory(request.category());
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=ProductServiceTest`
Expected: PASS (all cases).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/service/ProductService.java \
        online-shopping/src/test/java/com/shop/onlineshopping/service/ProductServiceTest.java
git commit -m "feat: add ProductService with list/get/create/update/delete"
```

---

### Task 4: ProductController (customer search — Module 2)

**Files:**
- Create: `.../controller/ProductController.java`
- Test: `.../controller/ProductControllerTest.java`

**Interfaces:**
- Consumes: `ProductService` (Task 3), `ProductResponse` (Task 2), `Category` (Task 1); Spring Data Web `Pageable`/`@PageableDefault`; auth from Plan 1 (`JwtService` to mint test tokens; `SecurityConfig` gates the route as authenticated).
- Produces:
  - `GET /api/products` → 200, `Page<ProductResponse>` (JSON has `content`, `totalElements`, `totalPages`, ...); optional `?category=<Category>`; `@PageableDefault(size = 20)`.
  - `GET /api/products/{id}` → 200 `ProductResponse`, or 404 if absent.

Note: returning `Page<ProductResponse>` emits a benign Spring `WARN` about `PageImpl` serialization; the JSON contract (`content` + `totalElements`) is stable and is what the tests assert. Do not switch to `PagedModel` — it changes the JSON shape.

- [ ] **Step 1: Write the failing test** (full-context MockMvc, real H2; products seeded via repository, tokens minted via `JwtService`)

Create `src/test/java/com/shop/onlineshopping/controller/ProductControllerTest.java`:

```java
package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired JwtService jwtService;

    private String customerToken;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        save("Laptop", Category.ELECTRONICS, "999.99", 5);
        save("Sofa", Category.FURNITURE, "499.00", 2);
        save("Lipstick", Category.COSMETICS, "12.50", 100);

        User customer = new User();
        customer.setEmail("cust@shop.com");
        customer.setRole(Role.CUSTOMER);
        customerToken = jwtService.generateToken(customer);
    }

    private void save(String name, Category category, String price, int qoh) {
        Product p = new Product();
        p.setName(name);
        p.setDescription(name + " description");
        p.setPrice(new BigDecimal(price));
        p.setQoh(qoh);
        p.setCategory(category);
        productRepository.save(p);
    }

    private String bearer() {
        return "Bearer " + customerToken;
    }

    @Test
    void listProducts_noToken_returns4xx() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listProducts_authenticated_returnsPagedProducts() throws Exception {
        mockMvc.perform(get("/api/products").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").exists())
                .andExpect(jsonPath("$.content[0].qoh").exists());
    }

    @Test
    void listProducts_filterByCategory_returnsOnlyThatCategory() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "ELECTRONICS")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Laptop"))
                .andExpect(jsonPath("$.content[0].category").value("ELECTRONICS"));
    }

    @Test
    void listProducts_invalidCategory_returns400() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "NOT_A_CATEGORY")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listProducts_pageBeyondRange_returnsEmptyContent() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "5").param("size", "2")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void getProduct_existing_returnsProduct() throws Exception {
        Long id = productRepository.findAll().get(0).getId();
        mockMvc.perform(get("/api/products/" + id).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.price").exists());
    }

    @Test
    void getProduct_missing_returns404() throws Exception {
        mockMvc.perform(get("/api/products/999999").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=ProductControllerTest`
Expected: FAIL — no `/api/products` controller (401/404 mismatches).

- [ ] **Step 3: Implement `ProductController`**

`src/main/java/com/shop/onlineshopping/controller/ProductController.java`:

```java
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
            @PageableDefault(size = 20) Pageable pageable) {
        return productService.listProducts(category, pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable Long id) {
        return productService.getProduct(id);
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=ProductControllerTest`
Expected: PASS (list, category filter, invalid category → 400, pagination, get, 404).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/controller/ProductController.java \
        online-shopping/src/test/java/com/shop/onlineshopping/controller/ProductControllerTest.java
git commit -m "feat: add ProductController for customer product search"
```

---

### Task 5: AdminProductController (products updation — Module 5)

**Files:**
- Create: `.../controller/AdminProductController.java`
- Test: `.../controller/AdminProductControllerTest.java`

**Interfaces:**
- Consumes: `ProductService` (Task 3), `ProductRequest`/`ProductResponse` (Task 2); auth from Plan 1 (`JwtService` to mint tokens; `SecurityConfig` gates `/api/admin/**` → ADMIN).
- Produces:
  - `POST /api/admin/products` → 201 `ProductResponse` (`@Valid ProductRequest`).
  - `PUT /api/admin/products/{id}` → 200 `ProductResponse` (`@Valid ProductRequest`), 404 if absent.
  - `DELETE /api/admin/products/{id}` → 204, 404 if absent.

- [ ] **Step 1: Write the failing test** (full-context MockMvc, real H2; admin + customer tokens minted via `JwtService`)

Create `src/test/java/com/shop/onlineshopping/controller/AdminProductControllerTest.java`:

```java
package com.shop.onlineshopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.onlineshopping.dto.ProductRequest;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminProductControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        adminToken = tokenFor(Role.ADMIN, "admin@shop.com");
        customerToken = tokenFor(Role.CUSTOMER, "cust@shop.com");
    }

    private String tokenFor(Role role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        return jwtService.generateToken(u);
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    private ProductRequest validRequest() {
        return new ProductRequest("Laptop", "A laptop", new BigDecimal("999.99"), 5, Category.ELECTRONICS);
    }

    private Long createProductAsAdmin() throws Exception {
        String body = mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void create_asAdmin_returns201AndPersists() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Laptop"))
                .andExpect(jsonPath("$.category").value("ELECTRONICS"));

        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    void create_asCustomer_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_noToken_returns4xx() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void create_negativePrice_returns400() throws Exception {
        ProductRequest bad = new ProductRequest("Bad", "x", new BigDecimal("-1.00"), 5, Category.ELECTRONICS);
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest());
        assertThat(productRepository.count()).isZero();
    }

    @Test
    void create_negativeQoh_returns400() throws Exception {
        ProductRequest bad = new ProductRequest("Bad", "x", new BigDecimal("1.00"), -5, Category.ELECTRONICS);
        mockMvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest());
        assertThat(productRepository.count()).isZero();
    }

    @Test
    void update_existing_asAdmin_returns200WithNewValues() throws Exception {
        Long id = createProductAsAdmin();

        ProductRequest update = new ProductRequest("Gaming Laptop", "Fast", new BigDecimal("1299.00"), 3, Category.ELECTRONICS);
        mockMvc.perform(put("/api/admin/products/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gaming Laptop"))
                .andExpect(jsonPath("$.qoh").value(3));
    }

    @Test
    void update_missing_returns404() throws Exception {
        mockMvc.perform(put("/api/admin/products/999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_existing_asAdmin_returns204() throws Exception {
        Long id = createProductAsAdmin();

        mockMvc.perform(delete("/api/admin/products/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(productRepository.existsById(id)).isFalse();
    }

    @Test
    void delete_missing_returns404() throws Exception {
        mockMvc.perform(delete("/api/admin/products/999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asCustomer_returns403() throws Exception {
        mockMvc.perform(delete("/api/admin/products/1")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=AdminProductControllerTest`
Expected: FAIL — no `/api/admin/products` controller.

- [ ] **Step 3: Implement `AdminProductController`**

`src/main/java/com/shop/onlineshopping/controller/AdminProductController.java`:

```java
package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.dto.ProductRequest;
import com.shop.onlineshopping.dto.ProductResponse;
import com.shop.onlineshopping.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=AdminProductControllerTest`
Expected: PASS (create/update/delete happy paths, 403 for customer, 4xx anon, 400 validation, 404 missing).

- [ ] **Step 5: Run the FULL test suite**

Run: `cd online-shopping && ./mvnw test`
Expected: BUILD SUCCESS — every test from Plan 1 + Plan 2 green.

- [ ] **Step 6: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/controller/AdminProductController.java \
        online-shopping/src/test/java/com/shop/onlineshopping/controller/AdminProductControllerTest.java
git commit -m "feat: add AdminProductController for admin product CRUD"
```

---

## Done criteria (Plan 2)

- `./mvnw test` is green (Plan 1 + Plan 2).
- An authenticated user can list products (`GET /api/products`, paginated), filter by category (`?category=ELECTRONICS`), and fetch one (`GET /api/products/{id}`); a missing id returns 404 and an unknown category returns 400.
- An ADMIN can add (`POST`), modify (`PUT`), and delete (`DELETE`) products under `/api/admin/products`; invalid payloads return 400 and a missing id returns 404.
- A CUSTOMER token on `/api/admin/products/**` returns 403; anonymous requests return 4xx.
- No changes to `pom.xml`, `application*.properties`, or any Plan 1 security/config class (only `GlobalExceptionHandler` gained two additive handlers).
- **Next plan:** Cart / Ordering / Payments (Plan 3) — builds on this `Product` catalog and `qoh`.
```