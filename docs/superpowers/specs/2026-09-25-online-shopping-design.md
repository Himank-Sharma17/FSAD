# Online Shopping Platform — Design Spec

**Date:** 2026-09-25
**Source of truth:** [fsad.md](../../../fsad.md)
**Status:** Draft for review

---

## 1. Overview

An online shopping / e-commerce platform with two user roles — **Customer** and **Admin**.
Customers browse products by category, manage a cart, place (and delete) orders, and send
feedback. Admins manage products, analyze sales, and view reports. Delivered in two phases:

- **Phase 1 (this spec's implementation target):** Backend REST API + MySQL database.
- **Phase 2 (later):** ReactJS single-page UI consuming the API.

### Scope note (conscious extension beyond the FSAD)
The FSAD does not mention payment. By explicit decision, ordering is implemented with a
**Stripe hosted-checkout + webhook** payment flow (plus email confirmation). This is a
deliberate scope expansion agreed during brainstorming to exercise a real webhook use case.
Everything else follows the FSAD as the single source of truth.

---

## 2. Tech Stack

FSAD Option 1.

| Layer | Choice |
|---|---|
| Language / Framework | Java + Spring Boot (Spring MVC REST) |
| Persistence | Spring Data JPA / Hibernate (ORM) |
| Database | MySQL |
| Auth | JWT (stateless), role-based (CUSTOMER / ADMIN) |
| API docs | springdoc-openapi (Swagger UI) |
| Payment | Stripe (hosted Checkout + inbound webhook) |
| Email | Spring Mail via Gmail SMTP |
| Real-time | WebSocket (STOMP) for admin dashboard |
| Logging | SLF4J + Logback (built-in) + request-logging interceptor |
| Frontend (Phase 2) | ReactJS + Stripe.js |

---

## 3. Architecture

**Backend module root:** `online-shopping/` (monorepo; Phase 2 React UI will be a sibling
`frontend/`). **Base Java package:** `com.shop.onlineshopping`. All backend classes live under
`online-shopping/src/main/java/com/shop/onlineshopping/<layer>/`, tests mirror the packages
under `src/test/java/...`, and config is in `online-shopping/src/main/resources/application.properties`.

Package layout by layer: `entity/`, `repository/`, `service/`, `controller/`, `dto/`,
`security/`, `config/`, `exception/`.

Classic layered Spring Boot REST API. React SPA (Phase 2) is a pure client.

```
React SPA  ──HTTP/JWT──▶  Spring Boot REST API  ──JPA/Hibernate──▶  MySQL
   ▲                          │        ▲
   └────── WebSocket ─────────┘        └── Stripe webhook (inbound) + Gmail SMTP (outbound)
```

Backend layers (each talks only to the layer below):

```
Controller  (REST endpoints, @Valid, role checks)
    │
Service     (business logic, transactions)
    │
Repository   (Spring Data JPA interfaces)
    │
Entity / MySQL
```

Cross-cutting: Security (JWT filter + role authorization), Config (Swagger, WebSocket, CORS,
Stripe), Exception handling (`@RestControllerAdvice`), DTOs (entities never exposed directly),
request-logging interceptor.

### Module → component map

| FSAD Module | Controller(s) | Key Service(s) |
|---|---|---|
| 1. Login/Registration | `AuthController` | `AuthService`, `JwtService` |
| 2. Products Search | `ProductController` | `ProductService` |
| 3. Ordering | `CartController`, `OrderController`, `CheckoutController`, `StripeWebhookController` | `CartService`, `OrderService`, `PaymentService`, `EmailService` |
| 4. Feedback | `FeedbackController` | `FeedbackService` |
| 5. Products Updation (admin) | `AdminProductController` | `ProductService` |
| 6. Sales Analysis (admin) | `SalesController` | `SalesAnalysisService` |
| 7. Reports (admin) | `ReportController` | `ReportService` |
| Real-time (admin) | WebSocket `/ws` | `NotificationService` |

---

## 4. Data Model

```
User (1) ── (1) Cart (1) ── (*) CartItem (*) ── (1) Product (*) ── (1) Category(enum)
  │                                                  │
  │ (1)                                              │
  ▼                                                  │
 (*) Order (1) ── (*) OrderItem (*) ─────────────────┘
  │
  ▼
 (*) Feedback
```

| Entity | Key fields | Notes |
|---|---|---|
| **User** | id, email, passwordHash, fullName, role (`CUSTOMER`/`ADMIN`), createdAt | Single table; role column drives access. |
| **Product** | id, name, description, price, qoh, category | The 5 FSAD fields + category enum. |
| **Category** | enum: `ELECTRONICS, ELECTRICAL, FURNITURE, COSMETICS, TOYS_AND_BOOKS` | Fixed list → Java enum, not a table. |
| **Cart** | id, user (1:1), items | One live cart per customer. |
| **CartItem** | id, cart, product, quantity | Add/remove = create/delete these. |
| **Order** | id, user, orderDate, status, totalAmount, stripeSessionId | status: `PENDING_PAYMENT → PAID → CANCELLED`. |
| **OrderItem** | id, order, product, quantity, priceAtPurchase | Snapshots price/qty; this IS the sales record. |
| **Feedback** | id, user, message, rating, createdAt | Customer submits; admin reports on it. |

### Design decisions
1. **Category is an enum** — fixed list of 5 per FSAD; never changes.
2. **No separate Sales table** — sales analysis/reports query `OrderItem`s of PAID orders,
   grouped by period / product / category. Fast-moving vs slow-moving = rank products by total
   quantity sold in a period. Single source of truth, no data drift.
3. **`priceAtPurchase` snapshot** — past orders reflect what the customer actually paid even if
   the product price changes later. QOH is decremented on PAID (via webhook), not at cart time.

---

## 5. API Surface

All routes under `/api`. Access: 🔓 public, 🧑 CUSTOMER, 🛡️ ADMIN. JWT filter enforces role.

**Auth (Module 1)**
| Method | Endpoint | Role | Purpose |
|---|---|---|---|
| POST | `/auth/register` | 🔓 | Customer self-registration |
| POST | `/auth/login` | 🔓 | Login → returns JWT (both roles) |

**Products Search (Module 2)**
| Method | Endpoint | Role | Purpose |
|---|---|---|---|
| GET | `/products` | 🧑 | All products (paginated) |
| GET | `/products/{id}` | 🧑 | Single product |
| GET | `/products?category=ELECTRONICS` | 🧑 | Category-wise view |

**Ordering (Module 3)**
| Method | Endpoint | Role | Purpose |
|---|---|---|---|
| GET | `/cart` | 🧑 | View my cart |
| POST | `/cart/items` | 🧑 | Add product to cart |
| DELETE | `/cart/items/{itemId}` | 🧑 | Remove from cart |
| POST | `/checkout` | 🧑 | Create Stripe Checkout Session → session URL |
| POST | `/stripe/webhook` | 🔓* | Stripe callback (*verified by signature, not JWT) |
| GET | `/orders` | 🧑 | My orders |
| DELETE | `/orders/{id}` | 🧑 | Delete/cancel my order |

**Feedback (Module 4)**
| Method | Endpoint | Role | Purpose |
|---|---|---|---|
| POST | `/feedback` | 🧑 | Submit feedback |

**Products Updation (Module 5)**
| Method | Endpoint | Role | Purpose |
|---|---|---|---|
| POST | `/admin/products` | 🛡️ | Add product |
| PUT | `/admin/products/{id}` | 🛡️ | Modify product |
| DELETE | `/admin/products/{id}` | 🛡️ | Delete product |

**Sales Analysis (Module 6)**
| Method | Endpoint | Role | Purpose |
|---|---|---|---|
| GET | `/admin/sales?period=WEEK\|MONTH\|QUARTER\|YEAR&groupBy=PRODUCT\|CATEGORY` | 🛡️ | Sales by period, product/category |
| GET | `/admin/sales/movement?period=...` | 🛡️ | Fast/slow-moving ranking |

**Reports (Module 7)**
| Method | Endpoint | Role | Purpose |
|---|---|---|---|
| GET | `/admin/reports/sales?period=...` | 🛡️ | Sales report |
| GET | `/admin/reports/feedback` | 🛡️ | Feedback report |

**WebSocket (real-time, admin)**
| Channel | Role | Pushed when |
|---|---|---|
| `/topic/admin/orders` | 🛡️ | New paid order |
| `/topic/admin/low-stock` | 🛡️ | Product QOH below threshold |
| `/topic/admin/feedback` | 🛡️ | New feedback submitted |

WebSocket handshake is JWT-authenticated so only admins subscribe to admin topics.

---

## 6. Cross-Cutting Concerns

### JWT Security
```
Login → AuthService verifies password (BCrypt) → JwtService signs { userId, role, expiry }
Every request → JwtAuthFilter reads Bearer token → verifies → sets Security context → allow/deny
```
- Passwords stored as BCrypt hashes.
- `SecurityConfig`: `/admin/**` → ADMIN, `/auth/**` and `/stripe/webhook` → public, rest → authenticated.
- **Pre-seeded admin** (not self-registerable): a `CommandLineRunner` inserts a fixed admin if
  none exists:
  - Username (email): `admin@shop.com`
  - Password: `Admin@123` (typed at login; stored BCrypt-hashed)
  - Values live in `application.properties`, overridable by env var.
- **AuthService** handles both `login()` and `register()` (single service by decision).

### Stripe payment flow
```
1. POST /checkout → PaymentService creates Stripe Session from cart, saves Order as
                    PENDING_PAYMENT + stripeSessionId, returns session URL
2. React redirects → customer pays on Stripe hosted page
3. Stripe → POST /stripe/webhook (checkout.session.completed)
4. Webhook.constructEvent(payload, sig, secret) → signature verified
5. On success: Order → PAID, decrement Product.qoh, clear cart, EmailService sends
               confirmation, push WebSocket order-feed event
6. Return 200 to Stripe (else Stripe retries)
```
- **Idempotency:** if order already PAID, do nothing (prevents double stock decrement).
- QOH decrement + email happen only here, on verified payment.

### Error handling
- One `@RestControllerAdvice` (`GlobalExceptionHandler`) → JSON `{ timestamp, status, error, message, path }`.
- Custom exceptions: `ResourceNotFoundException` (404), `InsufficientStockException` (409),
  `UnauthorizedException` (403), validation errors (400).

### Config
- `application.properties` for local; **secrets via env vars** (DB password, JWT secret,
  Stripe keys, Gmail app password) — never committed.
- `CorsConfig` allows React origin (`localhost:5173` dev).
- `SwaggerConfig` (springdoc) → `/swagger-ui.html`, with JWT "Authorize" button.
- `WebSocketConfig` (STOMP), `StripeConfig` (API key init).

### Logging
- SLF4J + Logback (built in), one logger per class. Emphasis: Stripe webhook events,
  auth success/failure, exception stack traces, QOH/low-stock changes.
- Request-logging interceptor (`@Component`) logs each HTTP call in/out.

---

## 7. Testing (Phase 1)

- **Unit** (JUnit 5 + Mockito): cart math, stock decrement, sales aggregation, fast/slow
  ranking, webhook idempotency.
- **Repository** (`@DataJpaTest`): custom queries (category filter, sales grouping).
- **Controller/integration** (`@SpringBootTest` + MockMvc): auth + role gating (401/403),
  happy paths.
- Stripe webhook: post a signed sample event; **Stripe CLI** (`stripe listen --forward-to
  localhost:8080/api/stripe/webhook`) for manual local verification.

---

## 8. Spring Initializr Dependencies

Generate the project with these. Metadata: Maven, Java 21, Spring Boot 4.1.1, packaging Jar,
Configuration = Properties. Group `com.shop`, Artifact `online-shopping`, Package
`com.shop.onlineshopping`.

Note: Spring Boot 4.1.1 (Spring Framework 7) — the three manually-added deps (jjwt,
springdoc-openapi, stripe-java) must use Spring Boot 4-compatible versions; pin them when
editing `pom.xml`.

### Selectable in Spring Initializr
| Dependency | Coordinates | Purpose |
|---|---|---|
| Spring Web (MVC) | `spring-boot-starter-webmvc` | REST controllers (Boot 4 renamed `-web` → `-webmvc`) |
| Spring Data JPA | `spring-boot-starter-data-jpa` | ORM persistence |
| MySQL Driver | `com.mysql:mysql-connector-j` | MySQL connection |
| Spring Security | `spring-boot-starter-security` | Auth + role gating |
| Validation | `spring-boot-starter-validation` | `@Valid` DTOs |
| Java Mail Sender | `spring-boot-starter-mail` | Confirmation emails |
| WebSocket | `spring-boot-starter-websocket` | Admin real-time |
| Lombok | `org.projectlombok:lombok` | Boilerplate reduction |
| Spring Boot DevTools | `spring-boot-devtools` | Dev convenience (optional) |

### Added manually to `pom.xml` (not in Initializr catalog)
| Dependency | Coordinates | Purpose |
|---|---|---|
| jjwt | `io.jsonwebtoken:jjwt-api` (+ `jjwt-impl`, `jjwt-jackson`) | JWT create/verify |
| springdoc-openapi | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | Swagger UI |
| stripe-java | `com.stripe:stripe-java` | Checkout + webhook verification |

### Included by default
Spring Boot 4 uses **modular test starters** (auto-added per starter): `spring-boot-starter-data-jpa-test`,
`-webmvc-test`, `-security-test`, `-validation-test`, `-mail-test`, `-websocket-test` — together
these provide JUnit 5, Mockito, MockMvc, `@DataJpaTest`, and Spring Security test support.

### Phase 2 (React) — npm, later
`@stripe/stripe-js`, `@stripe/react-stripe-js`, plus the React app scaffolding.

---

## 9. Out of Scope (Phase 1)
- React UI (Phase 2).
- Any feature not in the FSAD except the agreed Stripe payment + email + WebSocket additions.
- Admin category management (categories are a fixed enum).
