package com.shop.onlineshopping.config;

import com.shop.onlineshopping.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private static final String SESSION_PRINCIPAL_KEY = "STOMP_PRINCIPAL";

    private static final String ADMIN_TOPIC_PREFIX = "/topic/admin/";

    private final JwtService jwtService;

    public StompAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            return authenticate(message, accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            if (destination != null && destination.startsWith("/topic/")) {
                throw new MessagingException("Clients may not publish to broker destinations: " + destination);
            }
        }
        return message;
    }

    private Message<?> authenticate(Message<?> message, StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingException("Missing bearer token on STOMP CONNECT");
        }
        String token = header.substring(7);
        if (!jwtService.isValid(token)) {
            throw new MessagingException("Invalid token on STOMP CONNECT");
        }
        String email = jwtService.extractEmail(token);
        String role = jwtService.extractRole(token);

        UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));

        StompHeaderAccessor newAccessor = StompHeaderAccessor.wrap(message);
        newAccessor.setUser(principal);

        // Spring does not auto-propagate the CONNECT-frame principal to subsequent STOMP
        // frames (session.getPrincipal() stays null for JWT-only auth).  Store the principal
        // in the session-attributes map — which IS shared across all frames in this session —
        // so that authorizeSubscription() can retrieve it.
        Map<String, Object> sessionAttributes =
                SimpMessageHeaderAccessor.getSessionAttributes(newAccessor.getMessageHeaders());
        if (sessionAttributes != null) {
            sessionAttributes.put(SESSION_PRINCIPAL_KEY, principal);
        } else {
            log.warn("STOMP session attributes unavailable at CONNECT; cannot store principal for session {}",
                    accessor.getSessionId());
        }

        return MessageBuilder.createMessage(message.getPayload(), newAccessor.getMessageHeaders());
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(ADMIN_TOPIC_PREFIX)) {
            return;
        }
        // accessor.getUser() is null for SUBSCRIBE frames when auth was done via STOMP CONNECT
        // headers (as opposed to the HTTP handshake).  Fall back to the principal stored in the
        // session attributes by authenticate().
        Principal user = accessor.getUser();
        if (user == null) {
            Map<String, Object> attrs =
                    SimpMessageHeaderAccessor.getSessionAttributes(accessor.getMessageHeaders());
            if (attrs != null) {
                Object raw = attrs.get(SESSION_PRINCIPAL_KEY);
                if (raw instanceof Principal p) { user = p; }
            }
        }
        if (!(user instanceof UsernamePasswordAuthenticationToken auth)
                || auth.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new MessagingException("Admin role required to subscribe to " + destination);
        }
    }
}
