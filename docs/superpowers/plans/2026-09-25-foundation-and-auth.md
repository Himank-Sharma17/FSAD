# Foundation & Auth — Implementation Plan (Phase 1, Plan 1 of 5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Spring Boot backend foundation — data-layer config, JWT security, customer register/login, a pre-seeded admin, role gating, and Swagger UI — so every later plan builds on an authenticated, documented API.

**Architecture:** Classic layered Spring Boot REST API (controller → service → repository → entity). Stateless JWT auth: login issues a signed token; a `OncePerRequestFilter` validates it and sets the Spring Security context; `SecurityConfig` gates routes by role. Tests run against in-memory H2 (MySQL mode) so no live database is needed.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 · Spring Web MVC · Spring Data JPA/Hibernate · Spring Security · jjwt 0.13.0 · springdoc-openapi 3.1.1 · Lombok · MySQL (runtime) · H2 (test) · JUnit 5 + Mockito + MockMvc.

**Spec:** [docs/superpowers/specs/2026-09-25-online-shopping-design.md](../specs/2026-09-25-online-shopping-design.md)

## Global Constraints

- Module root: `online-shopping/`. Base package: `com.shop.onlineshopping`. Classes live under `<base>/<layer>/` (`entity`, `repository`, `service`, `controller`, `dto`, `security`, `config`, `exception`). Tests mirror packages under `src/test/java/`.
- Java 21, Spring Boot 4.1.1, Maven. Spring Web starter is `spring-boot-starter-webmvc`.
- Passwords stored as **BCrypt hashes** — never plaintext, never logged.
- Auth is **stateless JWT**; no HTTP session. Secrets (DB password, JWT secret, admin password) come from env vars with local defaults; never hard-code a production secret.
- Seeded admin: email `admin@shop.com`, password `Admin@123`, role `ADMIN`. Admin is **not** self-registerable; `/api/auth/register` always creates role `CUSTOMER`.
- **DTOs cross the wire; entities are never returned or accepted directly.** No entity carries a plaintext password.
- All endpoints under `/api`. Public: `/api/auth/**`, `/api/stripe/webhook`, `/swagger-ui/**`, `/v3/api-docs/**`. `/api/admin/**` → ADMIN. Everything else → authenticated.

## Review Focus

- **Duplicate-email registration** → registering an already-used email must return **409**, not a 500 or a second account. (Task 5)
- **Wrong password / unknown email at login** → must return **401**, not 500, and the response must not reveal which field was wrong. (Task 5)
- **Missing / malformed / expired JWT** on a protected route → **401**, never a 500 or a stack trace to the client. (Tasks 3, 6)
- **Privilege escalation via register** → a register payload cannot make itself ADMIN; role is forced to CUSTOMER server-side. (Task 5)
- **CUSTOMER hitting an ADMIN route** → a valid CUSTOMER token on `/api/admin/**` must return **403**, not 200. (Task 6)

---

## File Structure

```
online-shopping/
├── pom.xml                                        (modify: add jjwt, springdoc, h2)
├── src/main/resources/application.properties      (modify: datasource, jpa, jwt, admin)
├── src/test/resources/application-test.properties (create: H2 test config)
└── src/main/java/com/shop/onlineshopping/
    ├── entity/Role.java                           (create)
    ├── entity/User.java                           (create)
    ├── repository/UserRepository.java             (create)
    ├── dto/RegisterRequest.java                   (create)
    ├── dto/LoginRequest.java                      (create)
    ├── dto/AuthResponse.java                      (create)
    ├── dto/ApiError.java                          (create)
    ├── exception/DuplicateEmailException.java     (create)
    ├── exception/InvalidCredentialsException.java (create)
    ├── exception/GlobalExceptionHandler.java      (create)
    ├── service/JwtService.java                    (create)
    ├── service/AuthService.java                   (create)
    ├── security/JwtAuthFilter.java                (create)
    ├── security/SecurityConfig.java               (create)
    ├── controller/AuthController.java             (create)
    ├── config/AdminSeeder.java                    (create)
    └── config/OpenApiConfig.java                  (create)
```

---

### Task 1: Dependencies & configuration

**Files:**
- Modify: `online-shopping/pom.xml`
- Modify: `online-shopping/src/main/resources/application.properties`
- Create: `online-shopping/src/test/resources/application-test.properties`

**Interfaces:**
- Consumes: nothing.
- Produces: build with jjwt/springdoc/h2 available; config properties `app.jwt.secret`, `app.jwt.expiration-ms`, `app.admin.email`, `app.admin.password`; test profile `test` backed by H2.

- [ ] **Step 1: Add dependencies to `pom.xml`** (inside `<dependencies>`)

```xml
<!-- JWT -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.13.0</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
</dependency>
<!-- Swagger / OpenAPI (springdoc 3.x targets Spring Boot 4) -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.1.1</version>
</dependency>
<!-- In-memory DB for tests -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Fill `src/main/resources/application.properties`**

```properties
spring.application.name=online-shopping

# --- MySQL (runtime) ---
spring.datasource.url=jdbc:mysql://localhost:3306/online_shopping?createDatabaseIfNotExist=true&serverTimezone=UTC
spring.datasource.username=${DB_USERNAME:root}
spring.datasource.password=${DB_PASSWORD:root}
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# --- JWT ---
app.jwt.secret=${JWT_SECRET:local-dev-secret-change-me-please-at-least-32-bytes}
app.jwt.expiration-ms=${JWT_EXPIRATION_MS:86400000}

# --- Seeded admin ---
app.admin.email=${ADMIN_EMAIL:admin@shop.com}
app.admin.password=${ADMIN_PASSWORD:Admin@123}
```

- [ ] **Step 3: Create `src/test/resources/application-test.properties`**

```properties
spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect

app.jwt.secret=test-secret-key-that-is-at-least-32-bytes-long!!
app.jwt.expiration-ms=3600000
app.admin.email=admin@shop.com
app.admin.password=Admin@123
```

- [ ] **Step 4: Point the generated context test at the test profile**

Edit `src/test/java/com/shop/onlineshopping/OnlineShoppingApplicationTests.java` — add `@ActiveProfiles("test")`:

```java
package com.shop.onlineshopping;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class OnlineShoppingApplicationTests {

	@Test
	void contextLoads() {
	}

}
```

- [ ] **Step 5: Run the context test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=OnlineShoppingApplicationTests`
Expected: PASS — context boots on H2 with the new dependencies.

- [ ] **Step 6: Commit**

```bash
git add online-shopping/pom.xml online-shopping/src/main/resources/application.properties \
        online-shopping/src/test/resources/application-test.properties \
        online-shopping/src/test/java/com/shop/onlineshopping/OnlineShoppingApplicationTests.java
git commit -m "chore: add jwt/swagger/h2 deps and datasource + jwt + admin config"
```

---

### Task 2: Role enum, User entity, UserRepository

**Files:**
- Create: `.../entity/Role.java`, `.../entity/User.java`, `.../repository/UserRepository.java`
- Test: `.../repository/UserRepositoryTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum Role { CUSTOMER, ADMIN }`
  - `User` entity with getters/setters: `Long getId()`, `String getEmail()/setEmail`, `String getPasswordHash()/setPasswordHash`, `String getFullName()/setFullName`, `Role getRole()/setRole`, `Instant getCreatedAt()`.
  - `UserRepository extends JpaRepository<User, Long>` with `Optional<User> findByEmail(String)` and `boolean existsByEmail(String)`.

- [ ] **Step 1: Write the failing repository test**

Create `src/test/java/com/shop/onlineshopping/repository/UserRepositoryTest.java`:

```java
package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hashed");
        u.setFullName("Test User");
        u.setRole(Role.CUSTOMER);
        return u;
    }

    @Test
    void findByEmail_returnsSavedUser() {
        userRepository.save(newUser("a@shop.com"));

        Optional<User> found = userRepository.findByEmail("a@shop.com");

        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void existsByEmail_trueOnlyForSavedEmail() {
        userRepository.save(newUser("b@shop.com"));

        assertThat(userRepository.existsByEmail("b@shop.com")).isTrue();
        assertThat(userRepository.existsByEmail("missing@shop.com")).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=UserRepositoryTest`
Expected: FAIL — `User`, `Role`, `UserRepository` do not exist (compilation error).

- [ ] **Step 3: Create `Role`**

`src/main/java/com/shop/onlineshopping/entity/Role.java`:

```java
package com.shop.onlineshopping.entity;

public enum Role {
    CUSTOMER,
    ADMIN
}
```

- [ ] **Step 4: Create `User`**

`src/main/java/com/shop/onlineshopping/entity/User.java`:

```java
package com.shop.onlineshopping.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 5: Create `UserRepository`**

`src/main/java/com/shop/onlineshopping/repository/UserRepository.java`:

```java
package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

- [ ] **Step 6: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=UserRepositoryTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/entity/ \
        online-shopping/src/main/java/com/shop/onlineshopping/repository/ \
        online-shopping/src/test/java/com/shop/onlineshopping/repository/
git commit -m "feat: add User entity, Role enum, and UserRepository"
```

---

### Task 3: JwtService

**Files:**
- Create: `.../service/JwtService.java`
- Test: `.../service/JwtServiceTest.java`

**Interfaces:**
- Consumes: `User` (Task 2); properties `app.jwt.secret`, `app.jwt.expiration-ms` (Task 1).
- Produces: `JwtService` with `String generateToken(User)`, `String extractEmail(String)`, `String extractRole(String)`, `boolean isValid(String)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/shop/onlineshopping/service/JwtServiceTest.java`:

```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("test-secret-key-that-is-at-least-32-bytes-long!!", 3_600_000L);

    private User customer() {
        User u = new User();
        u.setEmail("c@shop.com");
        u.setRole(Role.CUSTOMER);
        return u;
    }

    @Test
    void generatedToken_roundTripsEmailAndRole() {
        String token = jwtService.generateToken(customer());

        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractEmail(token)).isEqualTo("c@shop.com");
        assertThat(jwtService.extractRole(token)).isEqualTo("CUSTOMER");
    }

    @Test
    void garbageToken_isNotValid() {
        assertThat(jwtService.isValid("not-a-jwt")).isFalse();
    }

    @Test
    void expiredToken_isNotValid() throws InterruptedException {
        JwtService shortLived =
                new JwtService("test-secret-key-that-is-at-least-32-bytes-long!!", 1L);
        String token = shortLived.generateToken(customer());
        Thread.sleep(5);

        assertThat(shortLived.isValid(token)).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=JwtServiceTest`
Expected: FAIL — `JwtService` does not exist.

- [ ] **Step 3: Implement `JwtService`**

`src/main/java/com/shop/onlineshopping/service/JwtService.java`:

```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public String extractEmail(String token) {
        return parse(token).getSubject();
    }

    public String extractRole(String token) {
        return parse(token).get("role", String.class);
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=JwtServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/service/JwtService.java \
        online-shopping/src/test/java/com/shop/onlineshopping/service/JwtServiceTest.java
git commit -m "feat: add JwtService for token generation and validation"
```

---

### Task 4: DTOs, custom exceptions, global error handler

**Files:**
- Create: `.../dto/RegisterRequest.java`, `.../dto/LoginRequest.java`, `.../dto/AuthResponse.java`, `.../dto/ApiError.java`
- Create: `.../exception/DuplicateEmailException.java`, `.../exception/InvalidCredentialsException.java`, `.../exception/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record RegisterRequest(String email, String password, String fullName)` (validated).
  - `record LoginRequest(String email, String password)` (validated).
  - `record AuthResponse(String token, String role)`.
  - `record ApiError(Instant timestamp, int status, String error, String message, String path)`.
  - `DuplicateEmailException extends RuntimeException` → 409; `InvalidCredentialsException extends RuntimeException` → 401.
  - `GlobalExceptionHandler` mapping those + validation (400) to `ApiError` JSON.

- [ ] **Step 1: Create the DTOs**

`dto/RegisterRequest.java`:

```java
package com.shop.onlineshopping.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6, message = "password must be at least 6 characters") String password,
        @NotBlank String fullName
) {}
```

`dto/LoginRequest.java`:

```java
package com.shop.onlineshopping.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {}
```

`dto/AuthResponse.java`:

```java
package com.shop.onlineshopping.dto;

public record AuthResponse(String token, String role) {}
```

`dto/ApiError.java`:

```java
package com.shop.onlineshopping.dto;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message, String path) {}
```

- [ ] **Step 2: Create the custom exceptions**

`exception/DuplicateEmailException.java`:

```java
package com.shop.onlineshopping.exception;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String email) {
        super("Email already registered: " + email);
    }
}
```

`exception/InvalidCredentialsException.java`:

```java
package com.shop.onlineshopping.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
```

- [ ] **Step 3: Create `GlobalExceptionHandler`**

`exception/GlobalExceptionHandler.java`:

```java
package com.shop.onlineshopping.exception;

import com.shop.onlineshopping.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(DuplicateEmailException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                message, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
```

- [ ] **Step 4: Compile to verify it builds**

Run: `cd online-shopping && ./mvnw test-compile`
Expected: BUILD SUCCESS (no test yet — these are exercised via Task 5 and Task 7).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/dto/ \
        online-shopping/src/main/java/com/shop/onlineshopping/exception/
git commit -m "feat: add auth DTOs, custom exceptions, and global error handler"
```

---

### Task 5: AuthService (register + login)

**Files:**
- Create: `.../service/AuthService.java`
- Test: `.../service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository` (Task 2), `JwtService` (Task 3), `PasswordEncoder` (Spring bean, defined in Task 6 — inject the interface `org.springframework.security.crypto.password.PasswordEncoder`), DTOs + exceptions (Task 4).
- Produces: `AuthService` with `AuthResponse register(RegisterRequest)` and `AuthResponse login(LoginRequest)`. `register` always sets role `CUSTOMER`.

- [ ] **Step 1: Write the failing test** (unit test, mocks — no Spring context)

Create `src/test/java/com/shop/onlineshopping/service/AuthServiceTest.java`:

```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.AuthResponse;
import com.shop.onlineshopping.dto.LoginRequest;
import com.shop.onlineshopping.dto.RegisterRequest;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.exception.DuplicateEmailException;
import com.shop.onlineshopping.exception.InvalidCredentialsException;
import com.shop.onlineshopping.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @InjectMocks AuthService authService;

    @Test
    void register_savesCustomerWithHashedPassword_andReturnsToken() {
        when(userRepository.existsByEmail("new@shop.com")).thenReturn(false);
        when(passwordEncoder.encode("secret1")).thenReturn("HASHED");
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");

        AuthResponse res = authService.register(
                new RegisterRequest("new@shop.com", "secret1", "New User"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("HASHED");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.CUSTOMER); // never ADMIN
        assertThat(res.token()).isEqualTo("jwt-token");
        assertThat(res.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void register_duplicateEmail_throws409() {
        when(userRepository.existsByEmail("dup@shop.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("dup@shop.com", "secret1", "Dup")))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_validCredentials_returnsToken() {
        User user = new User();
        user.setEmail("c@shop.com");
        user.setPasswordHash("HASHED");
        user.setRole(Role.CUSTOMER);
        when(userRepository.findByEmail("c@shop.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret1", "HASHED")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        AuthResponse res = authService.login(new LoginRequest("c@shop.com", "secret1"));

        assertThat(res.token()).isEqualTo("jwt-token");
    }

    @Test
    void login_unknownEmail_throws401() {
        when(userRepository.findByEmail("missing@shop.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("missing@shop.com", "x")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_wrongPassword_throws401() {
        User user = new User();
        user.setPasswordHash("HASHED");
        user.setRole(Role.CUSTOMER);
        when(userRepository.findByEmail("c@shop.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "HASHED")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("c@shop.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=AuthServiceTest`
Expected: FAIL — `AuthService` does not exist.

- [ ] **Step 3: Implement `AuthService`**

`src/main/java/com/shop/onlineshopping/service/AuthService.java`:

```java
package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.AuthResponse;
import com.shop.onlineshopping.dto.LoginRequest;
import com.shop.onlineshopping.dto.RegisterRequest;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.exception.DuplicateEmailException;
import com.shop.onlineshopping.exception.InvalidCredentialsException;
import com.shop.onlineshopping.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new DuplicateEmailException(req.email());
        }
        User user = new User();
        user.setEmail(req.email());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setFullName(req.fullName());
        user.setRole(Role.CUSTOMER); // customers can never self-assign ADMIN
        userRepository.save(user);
        return new AuthResponse(jwtService.generateToken(user), user.getRole().name());
    }

    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return new AuthResponse(jwtService.generateToken(user), user.getRole().name());
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=AuthServiceTest`
Expected: PASS (all 5 cases).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/service/AuthService.java \
        online-shopping/src/test/java/com/shop/onlineshopping/service/AuthServiceTest.java
git commit -m "feat: add AuthService with register and login"
```

---

### Task 6: Security — PasswordEncoder, JWT filter, SecurityConfig

**Files:**
- Create: `.../security/JwtAuthFilter.java`, `.../security/SecurityConfig.java`
- Test: `.../security/SecurityConfigTest.java`

**Interfaces:**
- Consumes: `JwtService` (Task 3).
- Produces: `PasswordEncoder` bean (BCrypt); `JwtAuthFilter` (validates Bearer token → sets `ROLE_<role>` authority); `SecurityFilterChain` gating routes (public auth/webhook/swagger, `/api/admin/**` = ADMIN, rest authenticated, stateless).

- [ ] **Step 1: Write the failing test** (full-context slice with a throwaway protected + admin endpoint)

Create `src/test/java/com/shop/onlineshopping/security/SecurityConfigTest.java`:

```java
package com.shop.onlineshopping.security;

import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;

    private String tokenFor(Role role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "@shop.com");
        u.setRole(role);
        return jwtService.generateToken(u);
    }

    @Test
    void adminRoute_noToken_returns401or403() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().is4xxClientError()); // unauthenticated → denied
    }

    @Test
    void adminRoute_customerToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/ping")
                        .header("Authorization", "Bearer " + tokenFor(Role.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoute_adminToken_passesSecurity_returns404NotForbidden() throws Exception {
        // No such controller yet → 404 proves security let it through (not 401/403)
        mockMvc.perform(get("/api/admin/ping")
                        .header("Authorization", "Bearer " + tokenFor(Role.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void garbageToken_onAuthenticatedRoute_returns401or403() throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().is4xxClientError());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=SecurityConfigTest`
Expected: FAIL — no `SecurityConfig`/`JwtAuthFilter`; default Spring Security blocks everything, so role-specific expectations fail.

- [ ] **Step 3: Implement `JwtAuthFilter`**

`src/main/java/com/shop/onlineshopping/security/JwtAuthFilter.java`:

```java
package com.shop.onlineshopping.security;

import com.shop.onlineshopping.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtService.isValid(token)) {
                String email = jwtService.extractEmail(token);
                String role = jwtService.extractRole(token);
                var auth = new UsernamePasswordAuthenticationToken(
                        email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Implement `SecurityConfig`**

`src/main/java/com/shop/onlineshopping/security/SecurityConfig.java`:

```java
package com.shop.onlineshopping.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/stripe/webhook",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 5: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=SecurityConfigTest`
Expected: PASS — customer→403, admin→404 (passed security), no/garbage token→4xx.

- [ ] **Step 6: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/security/ \
        online-shopping/src/test/java/com/shop/onlineshopping/security/
git commit -m "feat: add JWT auth filter, security config, and BCrypt encoder"
```

---

### Task 7: AuthController

**Files:**
- Create: `.../controller/AuthController.java`
- Test: `.../controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: `AuthService` (Task 5), DTOs (Task 4), security (Task 6).
- Produces: `POST /api/auth/register` → 201 + `AuthResponse`; `POST /api/auth/login` → 200 + `AuthResponse`.

- [ ] **Step 1: Write the failing test** (full-context MockMvc, real H2)

Create `src/test/java/com/shop/onlineshopping/controller/AuthControllerTest.java`:

```java
package com.shop.onlineshopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.onlineshopping.dto.LoginRequest;
import com.shop.onlineshopping.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    void register_thenLogin_returnsCustomerToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("alice@shop.com", "secret1", "Alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("alice@shop.com", "secret1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void register_duplicate_returns409() throws Exception {
        String body = json(new RegisterRequest("bob@shop.com", "secret1", "Bob"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(new RegisterRequest("carol@shop.com", "secret1", "Carol"))));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("carol@shop.com", "WRONG"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("not-an-email", "secret1", "X"))))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=AuthControllerTest`
Expected: FAIL — no `/api/auth/**` controller (404s).

- [ ] **Step 3: Implement `AuthController`**

`src/main/java/com/shop/onlineshopping/controller/AuthController.java`:

```java
package com.shop.onlineshopping.controller;

import com.shop.onlineshopping.dto.AuthResponse;
import com.shop.onlineshopping.dto.LoginRequest;
import com.shop.onlineshopping.dto.RegisterRequest;
import com.shop.onlineshopping.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=AuthControllerTest`
Expected: PASS (register/login happy path, 409, 401, 400).

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/controller/AuthController.java \
        online-shopping/src/test/java/com/shop/onlineshopping/controller/AuthControllerTest.java
git commit -m "feat: add AuthController for register and login endpoints"
```

---

### Task 8: Pre-seeded admin

**Files:**
- Create: `.../config/AdminSeeder.java`
- Test: `.../config/AdminSeederTest.java`

**Interfaces:**
- Consumes: `UserRepository` (Task 2), `PasswordEncoder` (Task 6); properties `app.admin.email`, `app.admin.password` (Task 1).
- Produces: `AdminSeeder implements CommandLineRunner` — inserts the admin if absent; idempotent.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/shop/onlineshopping/config/AdminSeederTest.java`:

```java
package com.shop.onlineshopping.config;

import com.shop.onlineshopping.dto.LoginRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class AdminSeederTest {

    @Autowired UserRepository userRepository;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void adminSeeded_onStartup_withAdminRole() {
        var admin = userRepository.findByEmail("admin@shop.com");
        assertThat(admin).isPresent();
        assertThat(admin.get().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void seededAdmin_canLogin_andGetsAdminRole() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin@shop.com", "Admin@123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void onlyOneAdmin_notDuplicated() {
        long count = userRepository.findAll().stream()
                .filter(u -> u.getEmail().equals("admin@shop.com")).count();
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=AdminSeederTest`
Expected: FAIL — no admin is seeded, so all three assertions fail.

- [ ] **Step 3: Implement `AdminSeeder`**

`src/main/java/com/shop/onlineshopping/config/AdminSeeder.java`:

```java
package com.shop.onlineshopping.config;

import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminSeeder(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.admin.email}") String adminEmail,
                       @Value("${app.admin.password}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }
        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setFullName("Administrator");
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=AdminSeederTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/config/AdminSeeder.java \
        online-shopping/src/test/java/com/shop/onlineshopping/config/AdminSeederTest.java
git commit -m "feat: seed fixed admin account on startup"
```

---

### Task 9: Swagger / OpenAPI with JWT authorize

**Files:**
- Create: `.../config/OpenApiConfig.java`
- Test: `.../config/OpenApiConfigTest.java`

**Interfaces:**
- Consumes: springdoc (Task 1), security (Task 6 — `/v3/api-docs/**` and `/swagger-ui/**` are public).
- Produces: OpenAPI doc served at `/v3/api-docs` with a `bearerAuth` (JWT) security scheme, and Swagger UI at `/swagger-ui.html`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/shop/onlineshopping/config/OpenApiConfigTest.java`:

```java
package com.shop.onlineshopping.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiConfigTest {

    @Autowired MockMvc mockMvc;

    @Test
    void apiDocs_isPublic_andListsAuthPath() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd online-shopping && ./mvnw test -Dtest=OpenApiConfigTest`
Expected: FAIL — no `bearerAuth` security scheme in the generated doc.

- [ ] **Step 3: Implement `OpenApiConfig`**

`src/main/java/com/shop/onlineshopping/config/OpenApiConfig.java`:

```java
package com.shop.onlineshopping.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SCHEME = "bearerAuth";

    @Bean
    public OpenAPI onlineShoppingOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Online Shopping API").version("v1")
                        .description("Phase 1 backend — customers, products, orders, admin"))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME))
                .components(new Components().addSecuritySchemes(SCHEME,
                        new SecurityScheme()
                                .name(SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `cd online-shopping && ./mvnw test -Dtest=OpenApiConfigTest`
Expected: PASS.

- [ ] **Step 5: Run the FULL test suite**

Run: `cd online-shopping && ./mvnw test`
Expected: BUILD SUCCESS — every test from Tasks 1–9 green.

- [ ] **Step 6: Commit**

```bash
git add online-shopping/src/main/java/com/shop/onlineshopping/config/OpenApiConfig.java \
        online-shopping/src/test/java/com/shop/onlineshopping/config/OpenApiConfigTest.java
git commit -m "feat: configure OpenAPI/Swagger with JWT bearer auth"
```

---

## Done criteria (Plan 1)

- `./mvnw test` is green.
- A customer can register (`POST /api/auth/register`) and log in (`POST /api/auth/login`) and receive a JWT.
- The seeded admin (`admin@shop.com` / `Admin@123`) logs in and receives an ADMIN token.
- `/api/admin/**` returns 403 for customers, 401 for anonymous; passes for admin.
- Swagger UI is live at `/swagger-ui.html` with a working "Authorize" (Bearer) button.
- **Next plan:** Product Catalog (Plan 2) — depends only on the security + error-handling foundation built here.
