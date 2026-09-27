package com.shop.onlineshopping.exception;

import com.shop.onlineshopping.dto.ApiError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class OrderingExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest req;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        req = new MockHttpServletRequest();
        req.setRequestURI("/api/checkout");
    }

    @Test
    void insufficientStock_returns409() {
        ResponseEntity<ApiError> response =
                handler.handleInsufficientStock(new InsufficientStockException("no stock"), req);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("no stock");
        assertThat(response.getBody().path()).isEqualTo("/api/checkout");
    }

    @Test
    void unauthorized_returns403() {
        ResponseEntity<ApiError> response =
                handler.handleUnauthorized(new UnauthorizedException("not yours"), req);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().message()).isEqualTo("not yours");
    }

    @Test
    void emptyCart_returns400() {
        ResponseEntity<ApiError> response =
                handler.handleEmptyCart(new EmptyCartException("cart empty"), req);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("cart empty");
    }

    @Test
    void webhookVerification_returns400() {
        ResponseEntity<ApiError> response =
                handler.handleWebhookVerification(new WebhookVerificationException("bad sig"), req);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("bad sig");
    }
}
