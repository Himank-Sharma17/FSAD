# Feedback, Sales Analysis & Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the three remaining admin-adjacent read/write features to the online-shopping backend — customer Feedback submission (Module 4), admin Sales Analysis (Module 6), and admin Reports (Module 7).

**Architecture:** Classic layered Spring Boot REST (Controller → Service → Repository → Entity/MySQL), matching Plans 1–3. Feedback adds one entity + repository + service + controller. Sales Analysis and Reports are read-only: they aggregate `OrderItem`s of `PAID` orders (no separate sales table — single source of truth per spec §4). Sales aggregation lives in a new `OrderItemRepository` (JPQL `@Query` constructor-expression projections into DTO records); `SalesAnalysisService` resolves a reporting period to a start `Instant` and routes group-by. `ReportService` reuses `SalesAnalysisService` + `FeedbackService` rather than re-querying (DRY).

**Tech Stack:** Java 21, Spring Boot 4.1.1 (Spring Framework 7), Spring Data JPA / Hibernate 6, MySQL (prod) / H2 (test), Spring Security (JWT), Bean Validation, JUnit 5 + Mockito + MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-25-online-shopping-design.md` (Modules 4, 6, 7 in §3/§5; data model §4; testing §7). Real-time WebSocket push (spec §5 WebSocket table) is explicitly deferred to Plan 5 and is **out of scope** here.

## Global Constraints

- Base Java package: `com.shop.onlineshopping`; layout by layer (`entity/`, `repository/`, `service/`, `controller/`, `dto/`, `exception/`). All backend code under `online-shopping/src/main/java/...`, tests mirror packages under `src/test/java/...`.
- Entities: Lombok `@Getter`/`@Setter`, `jakarta.persistence.*`, `@GeneratedValue(strategy = GenerationType.IDENTITY)`. DTOs are Java `record`s; response DTOs expose a `static from(...)` factory. **Entities are never returned from controllers** — always a DTO.
- Money is `BigDecimal(precision = 12, scale = 2)`.
- No changes to `pom.xml`, `application.properties`, `SecurityConfig`, or any `config/` class. `SecurityConfig` already gates `/api/admin/**` → `hasRole("ADMIN")`, `/api/auth/**` + `/api/stripe/webhook` public, and `anyRequest().authenticated()`. `/api/feedback` therefore requires authentication with no code change — consistent with how `/api/cart` and `/api/orders` (🧑 in the spec) are already only "authenticated", not CUSTOMER-role-restricted. Do **not** add method-level role security.
- Controllers resolve the caller via `Authentication authentication` → `userRepository.findByEmail(authentication.getName())`, throwing `ResourceNotFoundException` if absent (copy the `currentUser(...)` helper from `OrderController`).
- Only PAID orders count as sales. `PENDING_PAYMENT` and `CANCELLED` orders must be excluded from every sales/report figure.
- Spring Boot 4 moved test autoconfigure packages — use these exact imports in tests:
  - `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
  - `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager`
  - `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
  - `org.springframework.boot.test.context.SpringBootTest`
- `@DataJpaTest` runs in a single transaction returning L1-cached instances — inject `TestEntityManager em` and call `em.flush(); em.clear();` before reloading via a repository query.
- All tests carry `@ActiveProfiles("test")` (H2 in-memory, MODE=MySQL; see `src/test/resources/application-test.properties`).
- Maven build/test command (run from `online-shopping/`): `./mvnw test` (or `-Dtest=ClassName` for a single class).

## Review Focus

- **Feedback rating outside 1–5** (e.g. `0`, `6`, `-1`): must be rejected with `400` and nothing persisted. Pinned to Task 2 (validation) + Task 3 (controller test).
- **Blank/whitespace feedback message**: must be rejected with `400`, nothing persisted. Pinned to Task 2 + Task 3.
- **Invalid `period` or `groupBy` query value** (e.g. `?period=DECADE`): must return `400` (via the existing `MethodArgumentTypeMismatchException` handler), not `500`. Pinned to Task 6.
- **Sales query / sales report over a period with no PAID orders**: must return `200` with an empty list (sales) or zero totals + empty `byCategory` (report), never an error or `null` totals. Pinned to Task 6 + Task 8.
- **Feedback report when no feedback exists**: `averageRating` must be `0.0` (no divide-by-zero / `NaN`), `ratingCounts` present with all of 1–5 as `0`. Pinned to Task 7 + Task 8.

---

### Task 1: Feedback entity + FeedbackRepository

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/entity/Feedback.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/repository/FeedbackRepository.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/repository/FeedbackRepositoryTest.java`

**Interfaces:**
- Consumes: `User` entity (`entity/User.java`), `UserRepository`.
- Produces:
  - `Feedback` entity — fields `Long id`, `User user`, `String message`, `int rating`, `Instant createdAt` (defaults to `Instant.now()`), with Lombok getters/setters.
  - `FeedbackRepository extends JpaRepository<Feedback, Long>` with `List<Feedback> findAllByOrderByCreatedAtDesc()`.

- [ ] **Step 1: Write the failing repository test**

`FeedbackRepositoryTest.java`:

```java
package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Feedback;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class FeedbackRepositoryTest {

    @Autowired FeedbackRepository feedbackRepository;
    @Autowired UserRepository userRepository;
    @Autowired TestEntityManager em;

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Feedback newFeedback(User user, String message, int rating, Instant createdAt) {
        Feedback f = new Feedback();
        f.setUser(user);
        f.setMessage(message);
        f.setRating(rating);
        f.setCreatedAt(createdAt);
        return feedbackRepository.save(f);
    }

    @Test
    void findAllByOrderByCreatedAtDesc_returnsNewestFirst() {
        User user = newUser("fb@shop.com");
        newFeedback(user, "old", 3, Instant.now().minusSeconds(3600));
        newFeedback(user, "new", 5, Instant.now());

        em.flush();
        em.clear();

        List<Feedback> all = feedbackRepository.findAllByOrderByCreatedAtDesc();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getMessage()).isEqualTo("new");
        assertThat(all.get(1).getMessage()).isEqualTo("old");
        assertThat(all.get(0).getUser().getEmail()).isEqualTo("fb@shop.com");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FeedbackRepositoryTest`
Expected: FAIL to compile — `Feedback` and `FeedbackRepository` do not exist.

- [ ] **Step 3: Create the Feedback entity**

`Feedback.java`:

```java
package com.shop.onlineshopping.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "feedback")
@Getter
@Setter
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 4: Create the FeedbackRepository**

`FeedbackRepository.java`:

```java
package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    List<Feedback> findAllByOrderByCreatedAtDesc();
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=FeedbackRepositoryTest`
Expected: PASS (1 test).

- [ ] **Step 6: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/entity/Feedback.java \
        online-shopping/src/main/java/com/shop/onlineshopping/repository/FeedbackRepository.java \
        online-shopping/src/test/java/com/shop/onlineshopping/repository/FeedbackRepositoryTest.java
git commit -m "feat: add Feedback entity and repository"
```

---

### Task 2: Feedback DTOs + FeedbackService

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/FeedbackRequest.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/FeedbackResponse.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/service/FeedbackService.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/service/FeedbackServiceTest.java`

**Interfaces:**
- Consumes: `Feedback`, `FeedbackRepository` (Task 1), `User`.
- Produces:
  - `FeedbackRequest(String message, Integer rating)` — `@NotBlank message`, `@NotNull @Min(1) @Max(5) rating`.
  - `FeedbackResponse(Long id, String userEmail, String message, int rating, Instant createdAt)` with `static FeedbackResponse from(Feedback f)`.
  - `FeedbackService.submit(User user, FeedbackRequest request) -> FeedbackResponse` (sets user, message, rating; `createdAt` defaults on the entity; persists).
  - `FeedbackService.listAll() -> List<FeedbackResponse>` ordered newest-first (used by `ReportService` in Task 7).

- [ ] **Step 1: Write the failing service test**

`FeedbackServiceTest.java`:

```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackRequest;
import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.entity.Feedback;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.FeedbackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock FeedbackRepository feedbackRepository;
    @InjectMocks FeedbackService feedbackService;

    private User user(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    @Test
    void submit_persistsFeedbackForUserAndReturnsResponse() {
        User user = user(1L, "fb@shop.com");
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            f.setId(99L);
            return f;
        });

        FeedbackResponse response = feedbackService.submit(user, new FeedbackRequest("Great store", 5));

        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(captor.capture());
        Feedback saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getMessage()).isEqualTo("Great store");
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getCreatedAt()).isNotNull();

        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.userEmail()).isEqualTo("fb@shop.com");
        assertThat(response.rating()).isEqualTo(5);
    }

    @Test
    void listAll_mapsRepositoryResultsToResponsesNewestFirst() {
        User user = user(1L, "fb@shop.com");
        Feedback a = feedback(1L, user, "newest", 4, Instant.now());
        Feedback b = feedback(2L, user, "older", 2, Instant.now().minusSeconds(60));
        when(feedbackRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a, b));

        List<FeedbackResponse> all = feedbackService.listAll();

        assertThat(all).extracting(FeedbackResponse::message).containsExactly("newest", "older");
        assertThat(all).extracting(FeedbackResponse::rating).containsExactly(4, 2);
    }

    private Feedback feedback(Long id, User user, String message, int rating, Instant createdAt) {
        Feedback f = new Feedback();
        f.setId(id);
        f.setUser(user);
        f.setMessage(message);
        f.setRating(rating);
        f.setCreatedAt(createdAt);
        return f;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FeedbackServiceTest`
Expected: FAIL to compile — DTOs and `FeedbackService` do not exist.

- [ ] **Step 3: Create FeedbackRequest**

`FeedbackRequest.java`:

```java
package com.shop.onlineshopping.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FeedbackRequest(
        @NotBlank String message,
        @NotNull @Min(1) @Max(5) Integer rating
) {}
```

- [ ] **Step 4: Create FeedbackResponse**

`FeedbackResponse.java`:

```java
package com.shop.onlineshopping.dto;

import com.shop.onlineshopping.entity.Feedback;

import java.time.Instant;

public record FeedbackResponse(
        Long id,
        String userEmail,
        String message,
        int rating,
        Instant createdAt
) {
    public static FeedbackResponse from(Feedback f) {
        return new FeedbackResponse(
                f.getId(),
                f.getUser().getEmail(),
                f.getMessage(),
                f.getRating(),
                f.getCreatedAt());
    }
}
```

- [ ] **Step 5: Create FeedbackService**

`FeedbackService.java`:

```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackRequest;
import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.entity.Feedback;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final FeedbackRepository feedbackRepository;

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @Transactional
    public FeedbackResponse submit(User user, FeedbackRequest request) {
        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setMessage(request.message());
        feedback.setRating(request.rating());
        Feedback saved = feedbackRepository.save(feedback);
        log.info("Feedback {} submitted by user {}", saved.getId(), user.getId());
        return FeedbackResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<FeedbackResponse> listAll() {
        return feedbackRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(FeedbackResponse::from)
                .toList();
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=FeedbackServiceTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/dto/FeedbackRequest.java \
        online-shopping/src/main/java/com/shop/onlineshopping/dto/FeedbackResponse.java \
        online-shopping/src/main/java/com/shop/onlineshopping/service/FeedbackService.java \
        online-shopping/src/test/java/com/shop/onlineshopping/service/FeedbackServiceTest.java
git commit -m "feat: add feedback DTOs and FeedbackService"
```

---

### Task 3: FeedbackController

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/controller/FeedbackController.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/controller/FeedbackControllerTest.java`

**Interfaces:**
- Consumes: `FeedbackService.submit(...)` (Task 2), `UserRepository`, `ResourceNotFoundException`, `FeedbackRequest`/`FeedbackResponse`.
- Produces: `POST /api/feedback` — `@Valid @RequestBody FeedbackRequest` → `201 CREATED` with `FeedbackResponse` body. Requires authentication (enforced by existing `SecurityConfig`).

This task pins Review Focus: rating out of 1–5 → `400`; blank message → `400`. Invalid-body `400`s are produced by the existing `MethodArgumentNotValidException` handler in `GlobalExceptionHandler`.

- [ ] **Step 1: Write the failing controller test**

`FeedbackControllerTest.java`:

```java
package com.shop.onlineshopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.onlineshopping.dto.FeedbackRequest;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.FeedbackRepository;
import com.shop.onlineshopping.repository.UserRepository;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedbackControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired FeedbackRepository feedbackRepository;
    @Autowired JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String customerToken;

    @BeforeEach
    void setUp() {
        feedbackRepository.deleteAll();
        userRepository.deleteAll();
        User customer = newUser("fb@shop.com");
        customerToken = jwtService.generateToken(customer);
    }

    @AfterEach
    void tearDown() {
        feedbackRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    void submit_authenticated_returns201AndPersists() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new FeedbackRequest("Great service", 5))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.userEmail").value("fb@shop.com"))
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.message").value("Great service"));

        assertThat(feedbackRepository.count()).isEqualTo(1);
    }

    @Test
    void submit_anonymous_returns4xx() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new FeedbackRequest("Hi", 4))))
                .andExpect(status().is4xxClientError());
        assertThat(feedbackRepository.count()).isZero();
    }

    @Test
    void submit_ratingAboveFive_returns400() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new FeedbackRequest("Too high", 6))))
                .andExpect(status().isBadRequest());
        assertThat(feedbackRepository.count()).isZero();
    }

    @Test
    void submit_ratingZero_returns400() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new FeedbackRequest("Too low", 0))))
                .andExpect(status().isBadRequest());
        assertThat(feedbackRepository.count()).isZero();
    }

    @Test
    void submit_blankMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new FeedbackRequest("   ", 3))))
                .andExpect(status().isBadRequest());
        assertThat(feedbackRepository.count()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FeedbackControllerTest`
Expected: FAIL — `FeedbackController` does not exist (404s / compile error).

- [ ] **Step 3: Create FeedbackController**

`FeedbackController.java`:

```java
package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.dto.FeedbackRequest;
import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.exception.ResourceNotFoundException;
import com.shop.onlineshopping.repository.UserRepository;
import com.shop.onlineshopping.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final UserRepository userRepository;

    public FeedbackController(FeedbackService feedbackService, UserRepository userRepository) {
        this.feedbackService = feedbackService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<FeedbackResponse> submit(Authentication authentication,
                                                   @Valid @RequestBody FeedbackRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(feedbackService.submit(currentUser(authentication), request));
    }

    private User currentUser(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + authentication.getName()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=FeedbackControllerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/controller/FeedbackController.java \
        online-shopping/src/test/java/com/shop/onlineshopping/controller/FeedbackControllerTest.java
git commit -m "feat: add FeedbackController (POST /api/feedback)"
```

---

### Task 4: Sales-aggregation projections + OrderItemRepository + order-count query

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/SalesLineResponse.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/CategorySalesRow.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/ProductMovementResponse.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/repository/OrderItemRepository.java`
- Modify: `online-shopping/src/main/java/com/shop/onlineshopping/repository/OrderRepository.java` (add one derived count method)
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/repository/OrderItemRepositoryTest.java`

**Interfaces:**
- Consumes: `OrderItem`, `Order`, `Product`, `Category`, `OrderStatus` entities.
- Produces:
  - `SalesLineResponse(String label, Long totalQuantity, BigDecimal totalRevenue)` — API-facing sales line (label = product name, or category name after mapping in Task 5).
  - `CategorySalesRow(Category category, Long totalQuantity, BigDecimal totalRevenue)` — internal projection for category grouping (mapped to `SalesLineResponse` in Task 5; never returned from a controller).
  - `ProductMovementResponse(Long productId, String productName, Long totalQuantitySold)`.
  - `OrderItemRepository extends JpaRepository<OrderItem, Long>` with:
    - `List<SalesLineResponse> salesByProduct(Instant since)`
    - `List<CategorySalesRow> salesByCategory(Instant since)`
    - `List<ProductMovementResponse> movementRanking(Instant since)`
    - each filtering `order.status = PAID AND order.orderDate >= :since`.
  - `OrderRepository.countByStatusAndOrderDateGreaterThanEqual(OrderStatus status, Instant since) -> long`.

Note: JPQL constructor expressions require the fully-qualified record class name; `SUM(oi.quantity)` yields `Long`, `SUM(oi.quantity * oi.priceAtPurchase)` yields `BigDecimal`. Referencing the enum constant `com.shop.onlineshopping.entity.OrderStatus.PAID` inline in JPQL is supported by Hibernate 6.

- [ ] **Step 1: Write the failing repository test**

`OrderItemRepositoryTest.java`:

```java
package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.dto.CategorySalesRow;
import com.shop.onlineshopping.dto.ProductMovementResponse;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class OrderItemRepositoryTest {

    @Autowired OrderItemRepository orderItemRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired UserRepository userRepository;
    @Autowired TestEntityManager em;

    private User user;

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Product newProduct(String name, Category category, String price) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal(price));
        p.setQoh(100);
        p.setCategory(category);
        return productRepository.save(p);
    }

    private void newOrder(OrderStatus status, Instant date, Product product, int qty, String priceAtPurchase) {
        Order order = new Order();
        order.setUser(user);
        order.setStatus(status);
        order.setStripeSessionId("cs_" + System.nanoTime());
        order.setOrderDate(date);
        order.setTotalAmount(new BigDecimal(priceAtPurchase).multiply(BigDecimal.valueOf(qty)));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(qty);
        item.setPriceAtPurchase(new BigDecimal(priceAtPurchase));
        order.getItems().add(item);
        orderRepository.save(order);
    }

    @Test
    void salesByProduct_sumsOnlyPaidOrdersWithinPeriod() {
        user = newUser("sales@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Product lamp = newProduct("Lamp", Category.ELECTRICAL, "50.00");
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);

        newOrder(OrderStatus.PAID, recent, laptop, 2, "1000.00");   // counts: qty 2, rev 2000
        newOrder(OrderStatus.PAID, recent, lamp, 3, "50.00");       // counts: qty 3, rev 150
        newOrder(OrderStatus.PENDING_PAYMENT, recent, laptop, 5, "1000.00"); // excluded: not PAID
        newOrder(OrderStatus.PAID, old, laptop, 9, "1000.00");      // excluded: out of period

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<SalesLineResponse> lines = orderItemRepository.salesByProduct(since);

        assertThat(lines).hasSize(2);
        SalesLineResponse laptopLine = lines.stream()
                .filter(l -> l.label().equals("Laptop")).findFirst().orElseThrow();
        assertThat(laptopLine.totalQuantity()).isEqualTo(2L);
        assertThat(laptopLine.totalRevenue()).isEqualByComparingTo("2000.00");
        SalesLineResponse lampLine = lines.stream()
                .filter(l -> l.label().equals("Lamp")).findFirst().orElseThrow();
        assertThat(lampLine.totalQuantity()).isEqualTo(3L);
        assertThat(lampLine.totalRevenue()).isEqualByComparingTo("150.00");
    }

    @Test
    void salesByCategory_groupsPaidRevenueByCategory() {
        user = newUser("cat@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Product phone = newProduct("Phone", Category.ELECTRONICS, "500.00");
        Product lamp = newProduct("Lamp", Category.ELECTRICAL, "50.00");
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);

        newOrder(OrderStatus.PAID, recent, laptop, 1, "1000.00");
        newOrder(OrderStatus.PAID, recent, phone, 2, "500.00");   // ELECTRONICS total: qty 3, rev 2000
        newOrder(OrderStatus.PAID, recent, lamp, 4, "50.00");     // ELECTRICAL total: qty 4, rev 200

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<CategorySalesRow> rows = orderItemRepository.salesByCategory(since);

        assertThat(rows).hasSize(2);
        CategorySalesRow electronics = rows.stream()
                .filter(r -> r.category() == Category.ELECTRONICS).findFirst().orElseThrow();
        assertThat(electronics.totalQuantity()).isEqualTo(3L);
        assertThat(electronics.totalRevenue()).isEqualByComparingTo("2000.00");
        CategorySalesRow electrical = rows.stream()
                .filter(r -> r.category() == Category.ELECTRICAL).findFirst().orElseThrow();
        assertThat(electrical.totalQuantity()).isEqualTo(4L);
        assertThat(electrical.totalRevenue()).isEqualByComparingTo("200.00");
    }

    @Test
    void movementRanking_ordersByQuantitySoldDescending() {
        user = newUser("move@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Product lamp = newProduct("Lamp", Category.ELECTRICAL, "50.00");
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);

        newOrder(OrderStatus.PAID, recent, laptop, 2, "1000.00");
        newOrder(OrderStatus.PAID, recent, lamp, 10, "50.00");

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<ProductMovementResponse> ranking = orderItemRepository.movementRanking(since);

        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).productName()).isEqualTo("Lamp");
        assertThat(ranking.get(0).totalQuantitySold()).isEqualTo(10L);
        assertThat(ranking.get(1).productName()).isEqualTo("Laptop");
        assertThat(ranking.get(1).totalQuantitySold()).isEqualTo(2L);
    }

    @Test
    void salesByProduct_noPaidOrdersInPeriod_returnsEmpty() {
        user = newUser("empty@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        newOrder(OrderStatus.PENDING_PAYMENT, Instant.now(), laptop, 1, "1000.00");

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(orderItemRepository.salesByProduct(since)).isEmpty();
    }

    @Test
    void countByStatusAndOrderDateGreaterThanEqual_countsOnlyPaidInPeriod() {
        user = newUser("count@shop.com");
        Product laptop = newProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);

        newOrder(OrderStatus.PAID, recent, laptop, 1, "1000.00");
        newOrder(OrderStatus.PAID, recent, laptop, 1, "1000.00");
        newOrder(OrderStatus.PENDING_PAYMENT, recent, laptop, 1, "1000.00"); // not PAID
        newOrder(OrderStatus.PAID, old, laptop, 1, "1000.00");               // out of period

        em.flush();
        em.clear();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        long count = orderRepository.countByStatusAndOrderDateGreaterThanEqual(OrderStatus.PAID, since);
        assertThat(count).isEqualTo(2L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderItemRepositoryTest`
Expected: FAIL to compile — DTOs, `OrderItemRepository`, and the count method do not exist.

- [ ] **Step 3: Create the three projection DTOs**

`SalesLineResponse.java`:

```java
package com.shop.onlineshopping.dto;

import java.math.BigDecimal;

public record SalesLineResponse(String label, Long totalQuantity, BigDecimal totalRevenue) {}
```

`CategorySalesRow.java`:

```java
package com.shop.onlineshopping.dto;

import com.shop.onlineshopping.entity.Category;

import java.math.BigDecimal;

public record CategorySalesRow(Category category, Long totalQuantity, BigDecimal totalRevenue) {}
```

`ProductMovementResponse.java`:

```java
package com.shop.onlineshopping.dto;

public record ProductMovementResponse(Long productId, String productName, Long totalQuantitySold) {}
```

- [ ] **Step 4: Create OrderItemRepository**

`OrderItemRepository.java`:

```java
package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.dto.CategorySalesRow;
import com.shop.onlineshopping.dto.ProductMovementResponse;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("""
            SELECT new com.shop.onlineshopping.dto.SalesLineResponse(
                       oi.product.name, SUM(oi.quantity), SUM(oi.quantity * oi.priceAtPurchase))
            FROM OrderItem oi
            WHERE oi.order.status = com.shop.onlineshopping.entity.OrderStatus.PAID
              AND oi.order.orderDate >= :since
            GROUP BY oi.product.id, oi.product.name
            ORDER BY SUM(oi.quantity * oi.priceAtPurchase) DESC
            """)
    List<SalesLineResponse> salesByProduct(@Param("since") Instant since);

    @Query("""
            SELECT new com.shop.onlineshopping.dto.CategorySalesRow(
                       oi.product.category, SUM(oi.quantity), SUM(oi.quantity * oi.priceAtPurchase))
            FROM OrderItem oi
            WHERE oi.order.status = com.shop.onlineshopping.entity.OrderStatus.PAID
              AND oi.order.orderDate >= :since
            GROUP BY oi.product.category
            ORDER BY SUM(oi.quantity * oi.priceAtPurchase) DESC
            """)
    List<CategorySalesRow> salesByCategory(@Param("since") Instant since);

    @Query("""
            SELECT new com.shop.onlineshopping.dto.ProductMovementResponse(
                       oi.product.id, oi.product.name, SUM(oi.quantity))
            FROM OrderItem oi
            WHERE oi.order.status = com.shop.onlineshopping.entity.OrderStatus.PAID
              AND oi.order.orderDate >= :since
            GROUP BY oi.product.id, oi.product.name
            ORDER BY SUM(oi.quantity) DESC
            """)
    List<ProductMovementResponse> movementRanking(@Param("since") Instant since);
}
```

- [ ] **Step 5: Add the count method to OrderRepository**

Modify `OrderRepository.java` — add the import and one method:

```java
package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserOrderByOrderDateDesc(User user);
    Optional<Order> findByStripeSessionId(String stripeSessionId);
    long countByStatusAndOrderDateGreaterThanEqual(OrderStatus status, Instant since);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderItemRepositoryTest`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/dto/SalesLineResponse.java \
        online-shopping/src/main/java/com/shop/onlineshopping/dto/CategorySalesRow.java \
        online-shopping/src/main/java/com/shop/onlineshopping/dto/ProductMovementResponse.java \
        online-shopping/src/main/java/com/shop/onlineshopping/repository/OrderItemRepository.java \
        online-shopping/src/main/java/com/shop/onlineshopping/repository/OrderRepository.java \
        online-shopping/src/test/java/com/shop/onlineshopping/repository/OrderItemRepositoryTest.java
git commit -m "feat: add sales-aggregation queries (OrderItemRepository + order count)"
```

---

### Task 5: Sales period/group-by enums + SalesAnalysisService

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/SalesPeriod.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/SalesGroupBy.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/service/SalesAnalysisService.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/service/SalesAnalysisServiceTest.java`

**Interfaces:**
- Consumes: `OrderItemRepository` (Task 4), `SalesLineResponse`, `CategorySalesRow`, `ProductMovementResponse`, `Category`.
- Produces:
  - `SalesPeriod` enum `{ WEEK, MONTH, QUARTER, YEAR }` with `Instant since()` — WEEK=now−1 week, MONTH=now−1 month, QUARTER=now−3 months, YEAR=now−1 year, computed in UTC.
  - `SalesGroupBy` enum `{ PRODUCT, CATEGORY }`.
  - `SalesAnalysisService.sales(SalesPeriod period, SalesGroupBy groupBy) -> List<SalesLineResponse>` (CATEGORY maps `CategorySalesRow` → `SalesLineResponse` with `label = category.name()`).
  - `SalesAnalysisService.movement(SalesPeriod period) -> List<ProductMovementResponse>`.

- [ ] **Step 1: Write the failing service test**

`SalesAnalysisServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SalesAnalysisServiceTest`
Expected: FAIL to compile — enums and `SalesAnalysisService` do not exist.

- [ ] **Step 3: Create SalesPeriod**

`SalesPeriod.java`:

```java
package com.shop.onlineshopping.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public enum SalesPeriod {
    WEEK,
    MONTH,
    QUARTER,
    YEAR;

    /** Start of this reporting window, relative to now (UTC). */
    public Instant since() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime start = switch (this) {
            case WEEK -> now.minusWeeks(1);
            case MONTH -> now.minusMonths(1);
            case QUARTER -> now.minusMonths(3);
            case YEAR -> now.minusYears(1);
        };
        return start.toInstant();
    }
}
```

- [ ] **Step 4: Create SalesGroupBy**

`SalesGroupBy.java`:

```java
package com.shop.onlineshopping.dto;

public enum SalesGroupBy {
    PRODUCT,
    CATEGORY
}
```

- [ ] **Step 5: Create SalesAnalysisService**

`SalesAnalysisService.java`:

```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.ProductMovementResponse;
import com.shop.onlineshopping.dto.SalesGroupBy;
import com.shop.onlineshopping.dto.SalesLineResponse;
import com.shop.onlineshopping.dto.SalesPeriod;
import com.shop.onlineshopping.repository.OrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class SalesAnalysisService {

    private final OrderItemRepository orderItemRepository;

    public SalesAnalysisService(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }

    @Transactional(readOnly = true)
    public List<SalesLineResponse> sales(SalesPeriod period, SalesGroupBy groupBy) {
        Instant since = period.since();
        return switch (groupBy) {
            case PRODUCT -> orderItemRepository.salesByProduct(since);
            case CATEGORY -> orderItemRepository.salesByCategory(since).stream()
                    .map(row -> new SalesLineResponse(row.category().name(), row.totalQuantity(), row.totalRevenue()))
                    .toList();
        };
    }

    @Transactional(readOnly = true)
    public List<ProductMovementResponse> movement(SalesPeriod period) {
        return orderItemRepository.movementRanking(period.since());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=SalesAnalysisServiceTest`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/dto/SalesPeriod.java \
        online-shopping/src/main/java/com/shop/onlineshopping/dto/SalesGroupBy.java \
        online-shopping/src/main/java/com/shop/onlineshopping/service/SalesAnalysisService.java \
        online-shopping/src/test/java/com/shop/onlineshopping/service/SalesAnalysisServiceTest.java
git commit -m "feat: add SalesAnalysisService with period/groupBy resolution"
```

---

### Task 6: SalesController (admin)

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/controller/SalesController.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/controller/SalesControllerTest.java`

**Interfaces:**
- Consumes: `SalesAnalysisService` (Task 5), `SalesPeriod`, `SalesGroupBy`, `SalesLineResponse`, `ProductMovementResponse`.
- Produces:
  - `GET /api/admin/sales?period={WEEK|MONTH|QUARTER|YEAR}&groupBy={PRODUCT|CATEGORY}` → `List<SalesLineResponse>` (defaults `period=MONTH`, `groupBy=PRODUCT`).
  - `GET /api/admin/sales/movement?period={...}` → `List<ProductMovementResponse>` (default `period=MONTH`).
  - ADMIN-gated by existing `SecurityConfig` (`/api/admin/**`).

This task pins Review Focus: invalid `period`/`groupBy` → `400` (existing `MethodArgumentTypeMismatchException` handler); empty period → `200` empty list.

- [ ] **Step 1: Write the failing controller test**

`SalesControllerTest.java`:

```java
package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.OrderRepository;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.repository.UserRepository;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SalesControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired JwtService jwtService;

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        adminToken = tokenFor(Role.ADMIN, "admin@shop.com");
        customerToken = tokenFor(Role.CUSTOMER, "cust@shop.com");
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String tokenFor(Role role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        return jwtService.generateToken(u);
    }

    private User persistUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private void seedPaidOrder(User user, Product product, int qty, String price) {
        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PAID);
        order.setStripeSessionId("cs_" + System.nanoTime());
        order.setOrderDate(Instant.now());
        order.setTotalAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(qty);
        item.setPriceAtPurchase(new BigDecimal(price));
        order.getItems().add(item);
        orderRepository.save(order);
    }

    private Product persistProduct(String name, Category category, String price) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal(price));
        p.setQoh(100);
        p.setCategory(category);
        return productRepository.save(p);
    }

    @Test
    void sales_byProduct_asAdmin_returnsAggregatedLines() throws Exception {
        User user = persistUser("buyer@shop.com");
        Product laptop = persistProduct("Laptop", Category.ELECTRONICS, "1000.00");
        seedPaidOrder(user, laptop, 2, "1000.00");

        mockMvc.perform(get("/api/admin/sales?period=MONTH&groupBy=PRODUCT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Laptop"))
                .andExpect(jsonPath("$[0].totalQuantity").value(2))
                .andExpect(jsonPath("$[0].totalRevenue").value(2000.00));
    }

    @Test
    void sales_byCategory_asAdmin_labelsByCategoryName() throws Exception {
        User user = persistUser("buyer@shop.com");
        Product laptop = persistProduct("Laptop", Category.ELECTRONICS, "1000.00");
        seedPaidOrder(user, laptop, 1, "1000.00");

        mockMvc.perform(get("/api/admin/sales?period=YEAR&groupBy=CATEGORY")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("ELECTRONICS"));
    }

    @Test
    void movement_asAdmin_returnsRanking() throws Exception {
        User user = persistUser("buyer@shop.com");
        Product laptop = persistProduct("Laptop", Category.ELECTRONICS, "1000.00");
        Product lamp = persistProduct("Lamp", Category.ELECTRICAL, "50.00");
        seedPaidOrder(user, laptop, 2, "1000.00");
        seedPaidOrder(user, lamp, 10, "50.00");

        mockMvc.perform(get("/api/admin/sales/movement?period=MONTH")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Lamp"))
                .andExpect(jsonPath("$[0].totalQuantitySold").value(10));
    }

    @Test
    void sales_emptyPeriod_returns200EmptyList() throws Exception {
        mockMvc.perform(get("/api/admin/sales?period=WEEK&groupBy=PRODUCT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sales_asCustomer_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/sales")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void sales_anonymous_returns4xx() throws Exception {
        mockMvc.perform(get("/api/admin/sales"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void sales_invalidPeriod_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/sales?period=DECADE&groupBy=PRODUCT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SalesControllerTest`
Expected: FAIL — `SalesController` does not exist.

- [ ] **Step 3: Create SalesController**

`SalesController.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=SalesControllerTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/controller/SalesController.java \
        online-shopping/src/test/java/com/shop/onlineshopping/controller/SalesControllerTest.java
git commit -m "feat: add SalesController (GET /api/admin/sales, /movement)"
```

---

### Task 7: Report DTOs + ReportService

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/SalesReportResponse.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/FeedbackReportResponse.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/service/ReportService.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/service/ReportServiceTest.java`

**Interfaces:**
- Consumes: `SalesAnalysisService.sales(period, CATEGORY)` (Task 5), `OrderRepository.countByStatusAndOrderDateGreaterThanEqual` (Task 4), `FeedbackService.listAll()` (Task 2), `SalesLineResponse`, `FeedbackResponse`, `SalesPeriod`, `SalesGroupBy`, `OrderStatus`.
- Produces:
  - `SalesReportResponse(SalesPeriod period, Long totalItemsSold, BigDecimal totalRevenue, Long totalPaidOrders, List<SalesLineResponse> byCategory)`.
  - `FeedbackReportResponse(Long totalCount, Double averageRating, Map<Integer, Long> ratingCounts, List<FeedbackResponse> items)`.
  - `ReportService.salesReport(SalesPeriod period) -> SalesReportResponse` — `byCategory` from `SalesAnalysisService`; totals summed from those lines; `totalPaidOrders` from the count query.
  - `ReportService.feedbackReport() -> FeedbackReportResponse` — `averageRating` is `0.0` when empty; `ratingCounts` always has keys 1–5.

This task pins Review Focus: empty feedback → `averageRating = 0.0`, `ratingCounts` all zero; empty period → zero totals, empty `byCategory`.

- [ ] **Step 1: Write the failing service test**

`ReportServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ReportServiceTest`
Expected: FAIL to compile — report DTOs and `ReportService` do not exist.

- [ ] **Step 3: Create SalesReportResponse**

`SalesReportResponse.java`:

```java
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
```

- [ ] **Step 4: Create FeedbackReportResponse**

`FeedbackReportResponse.java`:

```java
package com.shop.onlineshopping.dto;

import java.util.List;
import java.util.Map;

public record FeedbackReportResponse(
        Long totalCount,
        Double averageRating,
        Map<Integer, Long> ratingCounts,
        List<FeedbackResponse> items
) {}
```

- [ ] **Step 5: Create ReportService**

`ReportService.java`:

```java
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
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=ReportServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/dto/SalesReportResponse.java \
        online-shopping/src/main/java/com/shop/onlineshopping/dto/FeedbackReportResponse.java \
        online-shopping/src/main/java/com/shop/onlineshopping/service/ReportService.java \
        online-shopping/src/test/java/com/shop/onlineshopping/service/ReportServiceTest.java
git commit -m "feat: add ReportService (sales report + feedback report)"
```

---

### Task 8: ReportController (admin)

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/controller/ReportController.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/controller/ReportControllerTest.java`

**Interfaces:**
- Consumes: `ReportService` (Task 7), `SalesPeriod`, `SalesReportResponse`, `FeedbackReportResponse`.
- Produces:
  - `GET /api/admin/reports/sales?period={...}` → `SalesReportResponse` (default `period=MONTH`).
  - `GET /api/admin/reports/feedback` → `FeedbackReportResponse`.
  - ADMIN-gated by existing `SecurityConfig`.

This task pins Review Focus: feedback report over an empty dataset returns `averageRating=0.0` end-to-end.

- [ ] **Step 1: Write the failing controller test**

`ReportControllerTest.java`:

```java
package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Feedback;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderItem;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.FeedbackRepository;
import com.shop.onlineshopping.repository.OrderRepository;
import com.shop.onlineshopping.repository.ProductRepository;
import com.shop.onlineshopping.repository.UserRepository;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired FeedbackRepository feedbackRepository;
    @Autowired JwtService jwtService;

    private String adminToken;
    private String customerToken;
    private User buyer;

    @BeforeEach
    void setUp() {
        feedbackRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        adminToken = tokenFor(Role.ADMIN, "admin@shop.com");
        customerToken = tokenFor(Role.CUSTOMER, "cust@shop.com");
        buyer = persistUser("buyer@shop.com");
    }

    @AfterEach
    void tearDown() {
        feedbackRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String tokenFor(Role role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        return jwtService.generateToken(u);
    }

    private User persistUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Buyer");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Product persistProduct(String name, Category category, String price) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal(price));
        p.setQoh(100);
        p.setCategory(category);
        return productRepository.save(p);
    }

    private void seedPaidOrder(Product product, int qty, String price) {
        Order order = new Order();
        order.setUser(buyer);
        order.setStatus(OrderStatus.PAID);
        order.setStripeSessionId("cs_" + System.nanoTime());
        order.setOrderDate(Instant.now());
        order.setTotalAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(qty);
        item.setPriceAtPurchase(new BigDecimal(price));
        order.getItems().add(item);
        orderRepository.save(order);
    }

    private void seedFeedback(String message, int rating) {
        Feedback f = new Feedback();
        f.setUser(buyer);
        f.setMessage(message);
        f.setRating(rating);
        feedbackRepository.save(f);
    }

    @Test
    void salesReport_asAdmin_returnsTotalsAndByCategory() throws Exception {
        Product laptop = persistProduct("Laptop", Category.ELECTRONICS, "1000.00");
        seedPaidOrder(laptop, 2, "1000.00");

        mockMvc.perform(get("/api/admin/reports/sales?period=MONTH")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("MONTH"))
                .andExpect(jsonPath("$.totalItemsSold").value(2))
                .andExpect(jsonPath("$.totalRevenue").value(2000.00))
                .andExpect(jsonPath("$.totalPaidOrders").value(1))
                .andExpect(jsonPath("$.byCategory[0].label").value("ELECTRONICS"));
    }

    @Test
    void feedbackReport_asAdmin_returnsAggregates() throws Exception {
        seedFeedback("great", 5);
        seedFeedback("ok", 3);

        mockMvc.perform(get("/api/admin/reports/feedback")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.averageRating").value(4.0))
                .andExpect(jsonPath("$.ratingCounts.5").value(1))
                .andExpect(jsonPath("$.ratingCounts.3").value(1))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void feedbackReport_noFeedback_averageIsZero() throws Exception {
        mockMvc.perform(get("/api/admin/reports/feedback")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.averageRating").value(0.0))
                .andExpect(jsonPath("$.ratingCounts.1").value(0));
    }

    @Test
    void salesReport_asCustomer_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/reports/sales")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void feedbackReport_anonymous_returns4xx() throws Exception {
        mockMvc.perform(get("/api/admin/reports/feedback"))
                .andExpect(status().is4xxClientError());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ReportControllerTest`
Expected: FAIL — `ReportController` does not exist.

- [ ] **Step 3: Create ReportController**

`ReportController.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ReportControllerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full suite to confirm no regressions**

Run: `./mvnw test`
Expected: PASS — all pre-existing tests (103 on merged main) plus the new Plan 4 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/controller/ReportController.java \
        online-shopping/src/test/java/com/shop/onlineshopping/controller/ReportControllerTest.java
git commit -m "feat: add ReportController (GET /api/admin/reports/sales, /feedback)"
```

---

## Notes for the executor

- **Scope guardrail:** Touch only the files named in each task. If any task appears to need changes to `pom.xml`, `application.properties`, `SecurityConfig`, or any `config/` class, STOP and report — Plan 4 was designed to require none. (Feedback is authenticated via the existing `anyRequest().authenticated()`; sales/reports are ADMIN via the existing `/api/admin/**` rule.)
- **Dependency order:** 1 → 2 → 3 (Feedback vertical); 4 → 5 → 6 (Sales vertical); 7 depends on Tasks 2, 4, 5; 8 depends on 7. Implement in numeric order.
- **WebSocket push is out of scope** (Plan 5). Do not add `NotificationService`, `/ws`, or `/topic/admin/*`.
