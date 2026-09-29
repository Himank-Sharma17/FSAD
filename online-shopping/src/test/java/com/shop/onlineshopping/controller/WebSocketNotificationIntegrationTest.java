package com.shop.onlineshopping.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
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
        // The server sends FeedbackResponse serialized as application/json via Jackson.
        // StringMessageConverter only handles text/plain and silently drops application/json frames,
        // so MappingJackson2MessageConverter is required to receive the push.
        client.setMessageConverter(new JacksonJsonMessageConverter());
        return client;
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient()
                .connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null, connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void adminReceivesFeedbackPush_whenCustomerSubmitsFeedback() throws Exception {
        StompSession session = connect(adminToken);
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();

        session.subscribe("/topic/admin/feedback", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return JsonNode.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
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

        JsonNode node = received.poll(5, TimeUnit.SECONDS);
        assertThat(node).isNotNull();
        assertThat(node.get("message").asText()).isEqualTo("Loved it");
        assertThat(node.get("rating").asInt()).isEqualTo(5);

        session.disconnect();
    }
}
