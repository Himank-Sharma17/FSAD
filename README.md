# Online Shopping Platform

An e-commerce platform with **Customer** and **Admin** roles, built in two phases.
See [fsad.md](fsad.md) for the source-of-truth requirements and
[docs/superpowers/specs/](docs/superpowers/specs/) for the design spec.

## Repository layout (monorepo)

```
FSAD/
├── fsad.md                     # Requirements — single source of truth
├── docs/superpowers/specs/     # Design specs (cover both phases)
├── online-shopping/            # Phase 1: Spring Boot REST API + MySQL
└── frontend/                   # Phase 2: ReactJS UI (added later)
```

## Phases

- **Phase 1 — Backend + DB** *(in progress)*: Spring Boot REST API, MySQL, JWT auth,
  Stripe checkout + webhook, email confirmation, WebSocket admin dashboard, Swagger UI.
- **Phase 2 — UI**: ReactJS single-page app consuming the API.

## Tech stack

Java 21 · Spring Boot 4.1.1 · Spring Data JPA / Hibernate · MySQL · Spring Security (JWT) ·
springdoc-openapi (Swagger) · Stripe · WebSocket (STOMP) · ReactJS (Phase 2).

## Backend — run (Phase 1)

```bash
cd online-shopping
./mvnw spring-boot:run
```

Swagger UI: `http://localhost:8080/swagger-ui.html`
