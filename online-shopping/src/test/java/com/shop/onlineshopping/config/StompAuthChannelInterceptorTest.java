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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        assertThat(accessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(((UsernamePasswordAuthenticationToken) accessor.getUser()).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
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

    @Test
    void preSend_sendFrame_passesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat");
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThat(interceptor.preSend(msg, channel)).isSameAs(msg);
    }

    // --- Session-attributes fallback path (no setUser; exercises the new retrieval logic) ---

    private Message<byte[]> subscribeMessageViaSessionAttrs(String destination, String role) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        // Do NOT call setUser — mimic the real STOMP flow where Spring does not auto-propagate
        // the CONNECT principal to subsequent frames.  Instead, populate session attributes the
        // same way StompAuthChannelInterceptor.authenticate() does.
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("STOMP_PRINCIPAL",
                new UsernamePasswordAuthenticationToken(
                        "u@shop.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        accessor.setSessionAttributes(attrs);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void preSend_subscribeAdminTopicAsAdmin_viaSessionAttrs_allowed() {
        Message<?> out = interceptor.preSend(
                subscribeMessageViaSessionAttrs("/topic/admin/orders", "ADMIN"), channel);
        assertThat(out).isNotNull();
    }

    @Test
    void preSend_subscribeAdminTopicAsCustomer_viaSessionAttrs_denied() {
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessageViaSessionAttrs("/topic/admin/orders", "CUSTOMER"), channel))
                .isInstanceOf(MessagingException.class);
    }
}
