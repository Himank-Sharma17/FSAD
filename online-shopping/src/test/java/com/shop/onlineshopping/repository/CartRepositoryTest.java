package com.shop.onlineshopping.repository;

import com.shop.onlineshopping.entity.Cart;
import com.shop.onlineshopping.entity.CartItem;
import com.shop.onlineshopping.entity.Category;
import com.shop.onlineshopping.entity.Product;
import com.shop.onlineshopping.entity.Role;
import com.shop.onlineshopping.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CartRepositoryTest {

    @Autowired CartRepository cartRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired TestEntityManager entityManager;

    private User newUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setFullName("Test User");
        u.setRole(Role.CUSTOMER);
        return userRepository.save(u);
    }

    private Product newProduct(String name) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal("9.99"));
        p.setQoh(10);
        p.setCategory(Category.ELECTRONICS);
        return productRepository.save(p);
    }

    @Test
    void findByUser_returnsCartWithItems() {
        User user = newUser("cart@shop.com");
        Product product = newProduct("Laptop");

        Cart cart = new Cart();
        cart.setUser(user);
        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProduct(product);
        item.setQuantity(2);
        cart.getItems().add(item);
        cartRepository.saveAndFlush(cart);

        entityManager.clear();
        Optional<Cart> found = cartRepository.findByUser(user);
        assertThat(found).isPresent();
        assertThat(found.get().getItems()).hasSize(1);
        assertThat(found.get().getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(found.get().getItems().get(0).getProduct().getName()).isEqualTo("Laptop");
    }

    @Test
    void removingItem_orphanRemovalDeletesCartItem() {
        User user = newUser("orphan@shop.com");
        Product product = newProduct("Mouse");

        Cart cart = new Cart();
        cart.setUser(user);
        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProduct(product);
        item.setQuantity(1);
        cart.getItems().add(item);
        cartRepository.saveAndFlush(cart);

        entityManager.clear();
        Cart managed = cartRepository.findByUser(user).orElseThrow();
        managed.getItems().clear();
        cartRepository.saveAndFlush(managed);

        entityManager.clear();
        Cart reloaded = cartRepository.findByUser(user).orElseThrow();
        assertThat(reloaded.getItems()).isEmpty();
    }
}
