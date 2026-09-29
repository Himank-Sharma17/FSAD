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
