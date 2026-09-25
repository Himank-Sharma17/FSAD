package com.shop.onlineshopping.config;

import com.shop.onlineshopping.dto.LoginRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

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
