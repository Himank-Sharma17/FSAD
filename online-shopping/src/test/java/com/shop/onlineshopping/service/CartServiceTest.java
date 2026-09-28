package com.shop.onlineshopping.service;

import com.shop.onlineshopping.dto.AddCartItemRequest;
import com.shop.onlineshopping.dto.CartResponse;
import com.shop.onlineshopping.entity.Cart;
import com.shop.onlineshopping.entity.CartItem;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.User;
import com.shop.onlineshopping.exception.ResourceNotFoundException;
import com.shop.onlineshopping.exception.UnauthorizedException;
import com.shop.onlineshopping.repository.CartItemRepository;
import com.shop.onlineshopping.repository.CartRepository;
import com.shop.onlineshopping.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock ProductRepository productRepository;
    @InjectMocks CartService cartService;

    private User user(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private Product product(Long id, String name, String price, int qoh) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setQoh(qoh);
        return p;
    }

    @Test
    void addItem_newProduct_createsCartItem() {
        User user = user(1L, "a@shop.com");
        Cart cart = new Cart();
        cart.setId(10L);
        cart.setUser(user);
        Product product = product(2L, "Laptop", "999.99", 5);

        when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(user, new AddCartItemRequest(2L, 3));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(3);
        assertThat(response.total()).isEqualByComparingTo("2999.97");
    }

    @Test
    void addItem_existingProduct_mergesQuantity() {
        User user = user(1L, "a@shop.com");
        Product product = product(2L, "Laptop", "999.99", 5);
        Cart cart = new Cart();
        cart.setId(10L);
        cart.setUser(user);
        CartItem existing = new CartItem();
        existing.setId(99L);
        existing.setCart(cart);
        existing.setProduct(product);
        existing.setQuantity(2);
        cart.getItems().add(existing);

        when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(user, new AddCartItemRequest(2L, 3));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(5);
    }

    @Test
    void addItem_unknownProduct_throwsResourceNotFound() {
        User user = user(1L, "a@shop.com");
        Cart cart = new Cart();
        cart.setUser(user);
        when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
        when(productRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addItem(user, new AddCartItemRequest(404L, 1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removeItem_notOwner_throwsUnauthorized() {
        User owner = user(1L, "owner@shop.com");
        User attacker = user(2L, "attacker@shop.com");
        Cart cart = new Cart();
        cart.setUser(owner);
        CartItem item = new CartItem();
        item.setId(77L);
        item.setCart(cart);
        when(cartItemRepository.findById(77L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> cartService.removeItem(attacker, 77L))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void removeItem_unknownItem_throwsResourceNotFound() {
        when(cartItemRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> cartService.removeItem(user(1L, "a@shop.com"), 404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
