package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.AuthResponse;
import com.shop.onlineshopping.dto.LoginRequest;
import com.shop.onlineshopping.dto.RegisterRequest;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.exception.DuplicateEmailException;
import com.shop.onlineshopping.exception.InvalidCredentialsException;
import com.shop.onlineshopping.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @InjectMocks AuthService authService;

    @Test
    void register_savesCustomerWithHashedPassword_andReturnsToken() {
        when(userRepository.existsByEmail("new@shop.com")).thenReturn(false);
        when(passwordEncoder.encode("secret1")).thenReturn("HASHED");
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");

        AuthResponse res = authService.register(
                new RegisterRequest("new@shop.com", "secret1", "New User"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("HASHED");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.CUSTOMER); // never ADMIN
        assertThat(res.token()).isEqualTo("jwt-token");
        assertThat(res.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void register_duplicateEmail_throws409() {
        when(userRepository.existsByEmail("dup@shop.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("dup@shop.com", "secret1", "Dup")))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_validCredentials_returnsToken() {
        User user = new User();
        user.setEmail("c@shop.com");
        user.setPasswordHash("HASHED");
        user.setRole(Role.CUSTOMER);
        when(userRepository.findByEmail("c@shop.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret1", "HASHED")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        AuthResponse res = authService.login(new LoginRequest("c@shop.com", "secret1"));

        assertThat(res.token()).isEqualTo("jwt-token");
    }

    @Test
    void login_unknownEmail_throws401() {
        when(userRepository.findByEmail("missing@shop.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("missing@shop.com", "x")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_wrongPassword_throws401() {
        User user = new User();
        user.setPasswordHash("HASHED");
        user.setRole(Role.CUSTOMER);
        when(userRepository.findByEmail("c@shop.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "HASHED")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("c@shop.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
