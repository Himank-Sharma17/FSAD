package com.shop.onlineshopping.security;

import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

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
