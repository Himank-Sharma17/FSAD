# Real-time & Cross-Cutting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add admin real-time WebSocket push (`/ws` + `/topic/admin/*`) and the deferred cross-cutting config (CORS for the React origin, a request-logging interceptor), completing all Phase-1 backend work.

**Architecture:** A STOMP-over-WebSocket broker (`WebSocketConfig`) exposes `/ws`; a `ChannelInterceptor` authenticates the STOMP `CONNECT` frame by reusing the existing `JwtService`, sets the Spring `Principal` + `ROLE_*` authority, and rejects non-admins subscribing to `/topic/admin/**`. A single `NotificationService` wraps `SimpMessagingTemplate` and is invoked from `OrderService.markOrderPaid` (new-paid-order + low-stock-crossing) and `FeedbackService.submit` (new feedback). Cross-cutting: a `CorsConfig` bean feeds Spring Security's existing `.cors()`, and a `RequestLoggingInterceptor` logs each HTTP call.

**Tech Stack:** Spring Boot 4.1.1 (Spring Framework 7), `spring-boot-starter-websocket` (already in `pom.xml`), STOMP simple broker, jjwt (existing `JwtService`), JUnit 5 + Mockito, `spring-boot-starter-websocket-test`, H2 for the `test` profile, Logback `ListAppender` for log assertions.

**Spec:** `docs/superpowers/specs/2026-09-25-online-shopping-design.md` (§5 WebSocket channels, §6 Cross-Cutting: Config/CORS/WebSocketConfig, Logging/request-logging interceptor).

## Global Constraints

- Base package `com.shop.onlineshopping`; Maven module in `online-shopping/`. All new files go under `online-shopping/src/main/java/com/shop/onlineshopping/` and tests under `online-shopping/src/test/java/com/shop/onlineshopping/`.
- **No `pom.xml` changes.** `spring-boot-starter-websocket` and `spring-boot-starter-websocket-test` are already present. If any task appears to need a new dependency, STOP and report.
- **Secrets/config via `application.properties` + env-var default**, never hard-coded. New keys follow the existing `${ENV_VAR:default}` idiom.
- WebSocket topics (exact, from spec §5): `/topic/admin/orders` (new paid order), `/topic/admin/low-stock` (QOH below threshold), `/topic/admin/feedback` (new feedback). Only admins may subscribe to `/topic/admin/**`.
- STOMP handshake auth happens at the **`CONNECT` frame** (Authorization: Bearer <jwt>), not the HTTP handshake — browsers cannot set headers on the WS handshake. The `/ws` HTTP handshake is therefore `permitAll()` in `SecurityConfig`; authentication is enforced by the STOMP `ChannelInterceptor`.
- Reuse existing `JwtService` (`isValid`, `extractEmail`, `extractRole`) and the `ROLE_ + role` authority convention already used in `JwtAuthFilter`.
- Tests run under `@ActiveProfiles("test")` (H2). Follow existing test conventions: `@SpringBootTest` + `@AutoConfigureMockMvc` for controller/integration tests; `@ExtendWith(MockitoExtension.class)` + `@InjectMocks` for service unit tests; `deleteAll()` cleanup in `@BeforeEach`/`@AfterEach`.
- Low-stock threshold is a single app-wide configurable value: `app.low-stock-threshold` (default 5, env-overridable). One threshold for the whole catalog; no per-product field.

## Review Focus

- **Notification failure must not roll back a paid order.** `NotificationService` is called inside the `@Transactional markOrderPaid`; if `convertAndSend` throws (broker hiccup), the payment, QOH decrement, cart-clear and email must still commit. Expectation: notification methods catch and log all exceptions internally and never propagate. *(Pinned in Task 3: `notify*_whenTemplateThrows_doesNotPropagate`.)*
- **STOMP `CONNECT` with a missing / malformed / expired JWT** must be rejected, never silently connected as an unauthenticated principal. *(Pinned in Task 4: `preSend_connectWithInvalidToken_rejects`.)*
- **A CUSTOMER subscribing to `/topic/admin/**`** must be denied even though their token is valid. *(Pinned in Task 4: `preSend_subscribeAdminTopicAsCustomer_denied`.)*
- **Low-stock boundary / repeat suppression.** Push fires exactly when a paid decrement takes QOH from above the threshold to at-or-below it (`previousQoh > threshold && newQoh <= threshold`); an already-low product decrementing further does not re-alert. *(Pinned in Task 3: `notifyLowStockIfCrossed_boundaryAndAlreadyLow`.)*
- **CORS from a disallowed origin.** A preflight from `http://localhost:5173` is allowed; a preflight from an unlisted origin does not receive an `Access-Control-Allow-Origin` header. *(Pinned in Task 1: `preflight_disallowedOrigin_noAllowHeader`.)*

---

### Task 1: CorsConfig (allow the React origin)

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/config/CorsConfig.java`
- Modify: `online-shopping/src/main/resources/application.properties` (add CORS origin property)
- Modify: `online-shopping/src/test/resources/application-test.properties` (add CORS origin property)
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/config/CorsConfigTest.java`

**Interfaces:**
- Consumes: Spring Security's existing `.cors(Customizer.withDefaults())` in `SecurityConfig`, which auto-detects a `CorsConfigurationSource` bean.
- Produces: `@Bean CorsConfigurationSource corsConfigurationSource()`.

- [ ] **Step 1: Add the CORS origin property to both properties files**

Append to `online-shopping/src/main/resources/application.properties`:
```properties

# CORS (Phase-2 React dev origin)
app.cors.allowed-origin=${CORS_ALLOWED_ORIGIN:http://localhost:5173}
```
Append to `online-shopping/src/test/resources/application-test.properties`:
```properties
app.cors.allowed-origin=http://localhost:5173
```

- [ ] **Step 2: Write the failing test**

Create `online-shopping/src/test/java/com/shop/onlineshopping/config/CorsConfigTest.java`:
```java
package com.shop.onlineshopping.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigTest {

    @Autowired MockMvc mockMvc;

    @Test
    void preflight_allowedOrigin_returnsAllowHeader() throws Exception {
        mockMvc.perform(options("/api/products")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void preflight_disallowedOrigin_noAllowHeader() throws Exception {
        mockMvc.perform(options("/api/products")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=CorsConfigTest`
Expected: FAIL — no `CorsConfigurationSource` bean, so the allowed origin is not echoed (allow-header assertion fails).

- [ ] **Step 4: Write minimal implementation**

Create `online-shopping/src/main/java/com/shop/onlineshopping/config/CorsConfig.java`:
```java
package com.shop.onlineshopping.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    private final String allowedOrigin;

    public CorsConfig(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd online-shopping && ./mvnw test -Dtest=CorsConfigTest`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/config/CorsConfig.java \
        online-shopping/src/main/resources/application.properties \
        online-shopping/src/test/resources/application-test.properties \
        online-shopping/src/test/java/com/shop/onlineshopping/config/CorsConfigTest.java
git commit -m "feat: add CorsConfig allowing the React dev origin"
```

---

### Task 2: RequestLoggingInterceptor + WebMvcConfig

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/config/RequestLoggingInterceptor.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/config/WebMvcConfig.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/config/RequestLoggingInterceptorTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `RequestLoggingInterceptor implements HandlerInterceptor` (`@Component`); `WebMvcConfig implements WebMvcConfigurer` registering it via `addInterceptors`.

- [ ] **Step 1: Write the failing test**

Create `online-shopping/src/test/java/com/shop/onlineshopping/config/RequestLoggingInterceptorTest.java`:
```java
package com.shop.onlineshopping.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestLoggingInterceptorTest {

    private final RequestLoggingInterceptor interceptor = new RequestLoggingInterceptor();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(RequestLoggingInterceptor.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void preHandle_logsMethodAndUri_andReturnsTrue() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/products");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("GET")
                        && e.getFormattedMessage().contains("/api/products"));
    }

    @Test
    void afterCompletion_logsStatus() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/feedback");
        when(response.getStatus()).thenReturn(201);

        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(appender.list)
                .anyMatch(e -> e.getFormattedMessage().contains("POST")
                        && e.getFormattedMessage().contains("/api/feedback")
                        && e.getFormattedMessage().contains("201"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=RequestLoggingInterceptorTest`
Expected: FAIL — `RequestLoggingInterceptor` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `online-shopping/src/main/java/com/shop/onlineshopping/config/RequestLoggingInterceptor.java`:
```java
package com.shop.onlineshopping.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        log.info("--> {} {}", request.getMethod(), request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, @Nullable Exception ex) {
        log.info("<-- {} {} {}", request.getMethod(), request.getRequestURI(), response.getStatus());
    }
}
```

Create `online-shopping/src/main/java/com/shop/onlineshopping/config/WebMvcConfig.java`:
```java
package com.shop.onlineshopping.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestLoggingInterceptor requestLoggingInterceptor;

    public WebMvcConfig(RequestLoggingInterceptor requestLoggingInterceptor) {
        this.requestLoggingInterceptor = requestLoggingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor).addPathPatterns("/api/**");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd online-shopping && ./mvnw test -Dtest=RequestLoggingInterceptorTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/config/RequestLoggingInterceptor.java \
        online-shopping/src/main/java/com/shop/onlineshopping/config/WebMvcConfig.java \
        online-shopping/src/test/java/com/shop/onlineshopping/config/RequestLoggingInterceptorTest.java
git commit -m "feat: add request-logging interceptor for /api/** calls"
```

---

### Task 3: NotificationService + LowStockEvent

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/dto/LowStockEvent.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/service/NotificationService.java`
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/service/NotificationServiceTest.java`

**Interfaces:**
- Consumes: `org.springframework.messaging.simp.SimpMessagingTemplate` (bean provided at runtime by the STOMP broker in Task 4; unit-tested here with a mock). `OrderResponse.from(Order)`, `FeedbackResponse` (existing DTOs). `Product` getters `getId()`, `getName()`, `getQoh()`. `Order` (passed to `OrderResponse.from`).
- Produces:
  - Destination constants: `ORDERS = "/topic/admin/orders"`, `LOW_STOCK = "/topic/admin/low-stock"`, `FEEDBACK = "/topic/admin/feedback"`.
  - `void notifyNewOrder(Order order)` — sends `OrderResponse.from(order)` to `ORDERS`.
  - `void notifyNewFeedback(FeedbackResponse feedback)` — sends `feedback` to `FEEDBACK`.
  - `void notifyLowStockIfCrossed(Product product, int previousQoh)` — if `previousQoh > threshold && product.getQoh() <= threshold`, sends `new LowStockEvent(product.getId(), product.getName(), product.getQoh())` to `LOW_STOCK`.
  - All three swallow and log any exception from `convertAndSend` (never propagate).
  - Constructor: `NotificationService(SimpMessagingTemplate messagingTemplate, @Value("${app.low-stock-threshold}") int lowStockThreshold)`.

- [ ] **Step 1: Add the threshold property to both properties files**

Append to `online-shopping/src/main/resources/application.properties`:
```properties

# Low-stock alert threshold (single app-wide value; push fires when a paid decrement crosses to <= this)
app.low-stock-threshold=${LOW_STOCK_THRESHOLD:5}
```
Append to `online-shopping/src/test/resources/application-test.properties`:
```properties
app.low-stock-threshold=5
```

- [ ] **Step 2: Write the failing test**

Create `online-shopping/src/test/java/com/shop/onlineshopping/service/NotificationServiceTest.java`:
```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.dto.LowStockEvent;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.OrderStatus;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NotificationServiceTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final NotificationService service = new NotificationService(template, 5);

    private Order order() {
        User u = new User();
        u.setId(1L);
        u.setEmail("buyer@shop.com");
        Order o = new Order();
        o.setId(7L);
        o.setUser(u);
        o.setStatus(OrderStatus.PAID);
        o.setOrderDate(Instant.now());
        o.setTotalAmount(new BigDecimal("10.00"));
        return o;
    }

    private Product product(int qoh) {
        Product p = new Product();
        p.setId(3L);
        p.setName("Widget");
        p.setQoh(qoh);
        return p;
    }

    @Test
    void notifyNewOrder_sendsOrderResponseToOrdersTopic() {
        service.notifyNewOrder(order());
        verify(template).convertAndSend(eq("/topic/admin/orders"), any(Object.class));
    }

    @Test
    void notifyNewFeedback_sendsToFeedbackTopic() {
        FeedbackResponse fb = new FeedbackResponse(9L, "buyer@shop.com", "Nice", 5, Instant.now());
        service.notifyNewFeedback(fb);
        verify(template).convertAndSend("/topic/admin/feedback", fb);
    }

    @Test
    void notifyLowStockIfCrossed_boundaryAndAlreadyLow() {
        // crosses from 6 down to 5 (== threshold) -> fires
        service.notifyLowStockIfCrossed(product(5), 6);
        ArgumentCaptor<LowStockEvent> captor = ArgumentCaptor.forClass(LowStockEvent.class);
        verify(template).convertAndSend(eq("/topic/admin/low-stock"), captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(3L);
        assertThat(captor.getValue().qoh()).isEqualTo(5);

        // already low (previous 5, not > threshold) -> no additional send
        service.notifyLowStockIfCrossed(product(4), 5);
        verify(template).convertAndSend(eq("/topic/admin/low-stock"), any(LowStockEvent.class));
    }

    @Test
    void notifyLowStockIfCrossed_staysAboveThreshold_doesNotSend() {
        service.notifyLowStockIfCrossed(product(8), 10);
        verify(template, never()).convertAndSend(eq("/topic/admin/low-stock"), any(LowStockEvent.class));
    }

    @Test
    void notify_whenTemplateThrows_doesNotPropagate() {
        doThrow(new RuntimeException("broker down"))
                .when(template).convertAndSend(any(String.class), any(Object.class));
        assertThatCode(() -> service.notifyNewOrder(order())).doesNotThrowAnyException();
        assertThatCode(() -> service.notifyNewFeedback(
                new FeedbackResponse(1L, "a@b.com", "m", 3, Instant.now()))).doesNotThrowAnyException();
        assertThatCode(() -> service.notifyLowStockIfCrossed(product(1), 6)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=NotificationServiceTest`
Expected: FAIL — `LowStockEvent` and `NotificationService` do not exist (compilation error).

- [ ] **Step 4: Write minimal implementation**

Create `online-shopping/src/main/java/com/shop/onlineshopping/dto/LowStockEvent.java`:
```java
package com.shop.onlineshopping.dto;

public record LowStockEvent(Long productId, String name, int qoh) {
}
```

Create `online-shopping/src/main/java/com/shop/onlineshopping/service/NotificationService.java`:
```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.FeedbackResponse;
import com.shop.onlineshopping.dto.LowStockEvent;
import com.shop.onlineshopping.dto.OrderResponse;
import com.shop.onlineshopping.entity.Order;
import com.shop.onlineshopping.entity.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    static final String ORDERS = "/topic/admin/orders";
    static final String LOW_STOCK = "/topic/admin/low-stock";
    static final String FEEDBACK = "/topic/admin/feedback";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final int lowStockThreshold;

    public NotificationService(SimpMessagingTemplate messagingTemplate,
                               @Value("${app.low-stock-threshold}") int lowStockThreshold) {
        this.messagingTemplate = messagingTemplate;
        this.lowStockThreshold = lowStockThreshold;
    }

    public void notifyNewOrder(Order order) {
        send(ORDERS, OrderResponse.from(order));
    }

    public void notifyNewFeedback(FeedbackResponse feedback) {
        send(FEEDBACK, feedback);
    }

    public void notifyLowStockIfCrossed(Product product, int previousQoh) {
        if (previousQoh > lowStockThreshold && product.getQoh() <= lowStockThreshold) {
            send(LOW_STOCK, new LowStockEvent(product.getId(), product.getName(), product.getQoh()));
        }
    }

    private void send(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (RuntimeException e) {
            // Never let a notification failure break the caller's transaction (e.g. a paid order).
            log.warn("Failed to push notification to {}: {}", destination, e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd online-shopping && ./mvnw test -Dtest=NotificationServiceTest`
Expected: PASS (all five tests).

- [ ] **Step 6: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/dto/LowStockEvent.java \
        online-shopping/src/main/java/com/shop/onlineshopping/service/NotificationService.java \
        online-shopping/src/main/resources/application.properties \
        online-shopping/src/test/resources/application-test.properties \
        online-shopping/src/test/java/com/shop/onlineshopping/service/NotificationServiceTest.java
git commit -m "feat: add NotificationService with resilient admin-topic pushes"
```

---

### Task 4: WebSocketConfig + StompAuthChannelInterceptor + SecurityConfig handshake permit

**Files:**
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/config/StompAuthChannelInterceptor.java`
- Create: `online-shopping/src/main/java/com/shop/onlineshopping/config/WebSocketConfig.java`
- Modify: `online-shopping/src/main/java/com/shop/onlineshopping/security/SecurityConfig.java:31-32` (permit `/ws/**` handshake)
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/config/StompAuthChannelInterceptorTest.java`

**Interfaces:**
- Consumes: `JwtService` (`isValid`, `extractEmail`, `extractRole`); `app.cors.allowed-origin` property (from Task 1) for the endpoint's allowed origin.
- Produces:
  - `StompAuthChannelInterceptor implements ChannelInterceptor` (`@Component`, constructor `StompAuthChannelInterceptor(JwtService)`): on `CONNECT`, reads native `Authorization: Bearer <jwt>`, validates, sets `accessor.setUser(UsernamePasswordAuthenticationToken(email, null, [ROLE_<role>]))`; throws `MessagingException` if the token is missing/invalid. On `SUBSCRIBE` to a destination starting `/topic/admin/`, throws `MessagingException` unless the session user has authority `ROLE_ADMIN`.
  - `WebSocketConfig` (`@Configuration @EnableWebSocketMessageBroker implements WebSocketMessageBrokerConfigurer`): broker `/topic`, app-prefix `/app`, endpoint `/ws` with allowed origin, inbound channel interceptor = the interceptor above.

- [ ] **Step 1: Write the failing test**

Create `online-shopping/src/test/java/com/shop/onlineshopping/config/StompAuthChannelInterceptorTest.java`:
```java
package com.shop.onlineshopping.config;

import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StompAuthChannelInterceptorTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtService);
    private final MessageChannel channel = mock(MessageChannel.class);

    private Message<byte[]> connectMessage(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, String role) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                "u@shop.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void preSend_connectWithValidAdminToken_setsPrincipal() {
        when(jwtService.isValid("good")).thenReturn(true);
        when(jwtService.extractEmail("good")).thenReturn("admin@shop.com");
        when(jwtService.extractRole("good")).thenReturn("ADMIN");

        Message<?> out = interceptor.preSend(connectMessage("Bearer good"), channel);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(out);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("admin@shop.com");
    }

    @Test
    void preSend_connectWithInvalidToken_rejects() {
        when(jwtService.isValid("bad")).thenReturn(false);
        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer bad"), channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void preSend_connectWithNoAuthHeader_rejects() {
        assertThatThrownBy(() -> interceptor.preSend(connectMessage(null), channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void preSend_subscribeAdminTopicAsAdmin_allowed() {
        Message<?> out = interceptor.preSend(subscribeMessage("/topic/admin/orders", "ADMIN"), channel);
        assertThat(out).isNotNull();
    }

    @Test
    void preSend_subscribeAdminTopicAsCustomer_denied() {
        assertThatThrownBy(() ->
                interceptor.preSend(subscribeMessage("/topic/admin/orders", "CUSTOMER"), channel))
                .isInstanceOf(MessagingException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=StompAuthChannelInterceptorTest`
Expected: FAIL — `StompAuthChannelInterceptor` does not exist (compilation error).

- [ ] **Step 3: Write the interceptor**

Create `online-shopping/src/main/java/com/shop/onlineshopping/config/StompAuthChannelInterceptor.java`:
```java
package com.shop.onlineshopping.config;

import com.shop.onlineshopping.service.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String ADMIN_TOPIC_PREFIX = "/topic/admin/";

    private final JwtService jwtService;

    public StompAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingException("Missing bearer token on STOMP CONNECT");
        }
        String token = header.substring(7);
        if (!jwtService.isValid(token)) {
            throw new MessagingException("Invalid token on STOMP CONNECT");
        }
        String email = jwtService.extractEmail(token);
        String role = jwtService.extractRole(token);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(ADMIN_TOPIC_PREFIX)) {
            return;
        }
        Principal user = accessor.getUser();
        if (!(user instanceof UsernamePasswordAuthenticationToken auth)
                || auth.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new MessagingException("Admin role required to subscribe to " + destination);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd online-shopping && ./mvnw test -Dtest=StompAuthChannelInterceptorTest`
Expected: PASS (all five tests).

- [ ] **Step 5: Write WebSocketConfig**

Create `online-shopping/src/main/java/com/shop/onlineshopping/config/WebSocketConfig.java`:
```java
package com.shop.onlineshopping.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;
    private final String allowedOrigin;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
                           @Value("${app.cors.allowed-origin}") String allowedOrigin) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigin);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
```

- [ ] **Step 6: Permit the `/ws` handshake in SecurityConfig**

In `online-shopping/src/main/java/com/shop/onlineshopping/security/SecurityConfig.java`, add `/ws/**` to the existing `permitAll()` matcher (lines 31-32). Change:
```java
                        .requestMatchers("/api/auth/**", "/api/stripe/webhook",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
```
to:
```java
                        .requestMatchers("/api/auth/**", "/api/stripe/webhook", "/ws/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
```

- [ ] **Step 7: Run the full suite to verify context loads and nothing regresses**

Run: `cd online-shopping && ./mvnw test`
Expected: PASS — the `@SpringBootTest` context now bootstraps the STOMP broker (proves `WebSocketConfig` + interceptor wire cleanly); all pre-existing tests remain green.

- [ ] **Step 8: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/config/StompAuthChannelInterceptor.java \
        online-shopping/src/main/java/com/shop/onlineshopping/config/WebSocketConfig.java \
        online-shopping/src/main/java/com/shop/onlineshopping/security/SecurityConfig.java \
        online-shopping/src/test/java/com/shop/onlineshopping/config/StompAuthChannelInterceptorTest.java
git commit -m "feat: add JWT-authenticated STOMP WebSocket broker for admin topics"
```

---

### Task 5: Wire NotificationService into FeedbackService

**Files:**
- Modify: `online-shopping/src/main/java/com/shop/onlineshopping/service/FeedbackService.java` (add dependency + push after save)
- Modify: `online-shopping/src/test/java/com/shop/onlineshopping/service/FeedbackServiceTest.java` (add mock + verify push)

**Interfaces:**
- Consumes: `NotificationService.notifyNewFeedback(FeedbackResponse)` (Task 3).
- Produces: no new external interface; `FeedbackService` constructor gains a `NotificationService` parameter.

- [ ] **Step 1: Add the failing expectation to FeedbackServiceTest**

In `online-shopping/src/test/java/com/shop/onlineshopping/service/FeedbackServiceTest.java`, add a mock field and a verification. Add to imports:
```java
import static org.mockito.Mockito.verify;
```
Add the mock field next to the existing `@Mock FeedbackRepository feedbackRepository;`:
```java
    @Mock NotificationService notificationService;
```
(The existing `@InjectMocks FeedbackService feedbackService;` will receive it automatically.)

Add this test method:
```java
    @Test
    void submit_pushesFeedbackNotification() {
        User user = user(1L, "fb@shop.com");
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            f.setId(42L);
            return f;
        });

        feedbackService.submit(user, new FeedbackRequest("Great store", 5));

        verify(notificationService).notifyNewFeedback(any(FeedbackResponse.class));
    }
```
Add the import for `FeedbackResponse` if not present:
```java
import com.shop.onlineshopping.dto.FeedbackResponse;
```
(It is already imported in the existing test — confirm before adding.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=FeedbackServiceTest`
Expected: FAIL — `FeedbackService` has no `NotificationService` dependency yet, so `notifyNewFeedback` is never called (and/or compilation fails on the constructor).

- [ ] **Step 3: Wire NotificationService into FeedbackService**

In `online-shopping/src/main/java/com/shop/onlineshopping/service/FeedbackService.java`, add the field, constructor param, and the push call after save. The class becomes:
```java
    private final FeedbackRepository feedbackRepository;
    private final NotificationService notificationService;

    public FeedbackService(FeedbackRepository feedbackRepository,
                           NotificationService notificationService) {
        this.feedbackRepository = feedbackRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public FeedbackResponse submit(User user, FeedbackRequest request) {
        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setMessage(request.message());
        feedback.setRating(request.rating());
        Feedback saved = feedbackRepository.save(feedback);
        log.info("Feedback {} submitted by user {}", saved.getId(), user.getId());
        FeedbackResponse response = FeedbackResponse.from(saved);
        notificationService.notifyNewFeedback(response);
        return response;
    }
```
(Leave `listAll()` unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd online-shopping && ./mvnw test -Dtest=FeedbackServiceTest`
Expected: PASS (existing tests + the new push test).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/service/FeedbackService.java \
        online-shopping/src/test/java/com/shop/onlineshopping/service/FeedbackServiceTest.java
git commit -m "feat: push new-feedback notification on submit"
```

---

### Task 6: Wire NotificationService into OrderService (new-order + low-stock)

**Files:**
- Modify: `online-shopping/src/main/java/com/shop/onlineshopping/service/OrderService.java` (add dependency; capture previous QOH; push low-stock per item + new-order after PAID)
- Modify: `online-shopping/src/test/java/com/shop/onlineshopping/service/OrderServiceTest.java` (add mock + verify pushes)

**Interfaces:**
- Consumes: `NotificationService.notifyLowStockIfCrossed(Product, int previousQoh)` and `notifyNewOrder(Order)` (Task 3).
- Produces: no new external interface; `OrderService` constructor gains a `NotificationService` parameter.

- [ ] **Step 1: Add the failing expectations to OrderServiceTest**

In `online-shopping/src/test/java/com/shop/onlineshopping/service/OrderServiceTest.java`, add the mock field beside the existing mocks:
```java
    @Mock NotificationService notificationService;
```
(The existing `@InjectMocks OrderService orderService;` wires it automatically.)

Add these test methods (imports `org.mockito.ArgumentMatchers.anyInt`, `org.mockito.ArgumentMatchers.eq`, `org.mockito.Mockito.verify` — add any not already present):
```java
    @Test
    void markOrderPaid_pushesNewOrderNotification() {
        User user = user(1L);
        Order order = pendingOrder(user, 5, 2);
        when(orderRepository.findByStripeSessionId("cs_test_123")).thenReturn(Optional.of(order));

        orderService.markOrderPaid("cs_test_123");

        verify(notificationService).notifyNewOrder(order);
    }

    @Test
    void markOrderPaid_pushesLowStockWithPreviousQoh() {
        User user = user(1L);
        Order order = pendingOrder(user, 6, 2); // qoh 6 -> 4, previous 6
        when(orderRepository.findByStripeSessionId("cs_test_123")).thenReturn(Optional.of(order));

        orderService.markOrderPaid("cs_test_123");

        // previousQoh passed is the value before decrement (6); NotificationService decides crossing
        verify(notificationService).notifyLowStockIfCrossed(order.getItems().get(0).getProduct(), 6);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=OrderServiceTest`
Expected: FAIL — `OrderService` has no `NotificationService` dependency; the new verifications fail (and/or compilation fails on the constructor).

- [ ] **Step 3: Wire NotificationService into OrderService**

In `online-shopping/src/main/java/com/shop/onlineshopping/service/OrderService.java`:

Add the field and constructor param (append to the existing four dependencies):
```java
    private final EmailService emailService;
    private final NotificationService notificationService;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        CartService cartService,
                        EmailService emailService,
                        NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.cartService = cartService;
        this.emailService = emailService;
        this.notificationService = notificationService;
    }
```

In `markOrderPaid`, capture the previous QOH before decrementing and push low-stock after saving each product; push the new-order event after the order is marked PAID. The decrement loop and tail become:
```java
        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            int previousQoh = product.getQoh();
            int newQoh = previousQoh - item.getQuantity();
            if (newQoh < 0) {
                log.warn("QOH for product {} would go negative ({} - {}); clamping to 0",
                        product.getId(), previousQoh, item.getQuantity());
                newQoh = 0;
            }
            product.setQoh(newQoh);
            productRepository.save(product);
            notificationService.notifyLowStockIfCrossed(product, previousQoh);
        }

        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);
        cartService.clearCart(order.getUser());
        emailService.sendOrderConfirmation(order);
        notificationService.notifyNewOrder(order);
        log.info("Order {} marked PAID", order.getId());
```
(Leave `listOrders` and `deleteOrder` unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd online-shopping && ./mvnw test -Dtest=OrderServiceTest`
Expected: PASS (existing markOrderPaid tests + the two new push tests).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/service/OrderService.java \
        online-shopping/src/test/java/com/shop/onlineshopping/service/OrderServiceTest.java
git commit -m "feat: push new-order and low-stock notifications on paid order"
```

---

### Task 7: End-to-end STOMP integration test

**Files:**
- Test: `online-shopping/src/test/java/com/shop/onlineshopping/controller/WebSocketNotificationIntegrationTest.java`

**Interfaces:**
- Consumes: the running app on a random port (`/ws` endpoint, `/topic/admin/feedback`), `POST /api/feedback`, `JwtService`, `UserRepository`, `FeedbackRepository`, entity `Role`.
- Produces: nothing (verification only).

- [ ] **Step 1: Write the end-to-end test**

Create `online-shopping/src/test/java/com/shop/onlineshopping/controller/WebSocketNotificationIntegrationTest.java`:
```java
package com.shop.onlineshopping.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebSocketNotificationIntegrationTest {

    @LocalServerPort int port;
    @Autowired UserRepository userRepository;
    @Autowired FeedbackRepository feedbackRepository;
    @Autowired JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        feedbackRepository.deleteAll();
        userRepository.deleteAll();
        adminToken = jwtService.generateToken(user("ws-admin@shop.com", Role.ADMIN));
        customerToken = jwtService.generateToken(user("ws-buyer@shop.com", Role.CUSTOMER));
    }

    @AfterEach
    void tearDown() {
        feedbackRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User user(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("WS User");
        u.setRole(role);
        return userRepository.save(u);
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        return client;
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient()
                .connectAsync("ws://localhost:" + port + "/ws", null, connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void adminReceivesFeedbackPush_whenCustomerSubmitsFeedback() throws Exception {
        StompSession session = connect(adminToken);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session.subscribe("/topic/admin/feedback", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                received.add((String) payload);
            }
        });

        // Submit feedback over REST as the customer -> should trigger the push.
        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(customerToken);
        String body = objectMapper.writeValueAsString(new FeedbackRequest("Loved it", 5));
        rest.postForEntity("http://localhost:" + port + "/api/feedback",
                new HttpEntity<>(body, headers), String.class);

        String message = received.poll(5, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        JsonNode node = objectMapper.readTree(message);
        assertThat(node.get("message").asText()).isEqualTo("Loved it");
        assertThat(node.get("rating").asInt()).isEqualTo(5);

        session.disconnect();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `cd online-shopping && ./mvnw test -Dtest=WebSocketNotificationIntegrationTest`
Expected: PASS — the admin STOMP client receives the feedback event pushed by `FeedbackService` → `NotificationService`. (The CUSTOMER-denied-subscription path is already unit-covered in Task 4's `preSend_subscribeAdminTopicAsCustomer_denied`.)

- [ ] **Step 3: Run the full suite**

Run: `cd online-shopping && ./mvnw test`
Expected: PASS — full green (138 pre-existing + Plan 5 additions).

- [ ] **Step 4: Commit**

```bash
git add online-shopping/src/test/java/com/shop/onlineshopping/controller/WebSocketNotificationIntegrationTest.java
git commit -m "test: end-to-end STOMP push of feedback to admin topic"
```

---

## Notes for the executor

- **Worktree:** Per project discipline, execute this plan on an isolated branch/worktree created via `superpowers:using-git-worktrees` at execution start.
- **Test command:** the module uses the Maven wrapper at `online-shopping/mvnw`. Run tests from the `online-shopping/` directory.
- **Model tiers (SDD):** haiku for verbatim-transcription tasks (Task 1 config, Task 3 DTO+service), sonnet for integration tasks touching existing services and the STOMP/MockMvc/e2e tests (Tasks 2, 4, 5, 6, 7) and ALL reviewers; opus for the final whole-branch review.
- **After all tasks:** run the final whole-branch review on the most capable model, then `superpowers:finishing-a-development-branch`.
</content>
</invoke>
