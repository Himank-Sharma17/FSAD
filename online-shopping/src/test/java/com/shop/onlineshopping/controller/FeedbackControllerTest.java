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

    @Test
    void submit_messageTooLong_returns400() throws Exception {
        String tooLong = "x".repeat(2001);
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new FeedbackRequest(tooLong, 3))))
                .andExpect(status().isBadRequest());
        assertThat(feedbackRepository.count()).isZero();
    }
}
