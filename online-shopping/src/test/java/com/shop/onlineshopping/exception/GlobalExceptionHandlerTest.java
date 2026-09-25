package com.shop.onlineshopping.exception;

import com.shop.onlineshopping.dto.ApiError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest req;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        req = new MockHttpServletRequest();
        req.setRequestURI("/api/auth/register");
    }

    @Test
    void handleDataIntegrity_returns409WithCorrectBody() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("dup");

        ResponseEntity<ApiError> response = handler.handleDataIntegrity(ex, req);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.path()).isEqualTo("/api/auth/register");
    }
}
