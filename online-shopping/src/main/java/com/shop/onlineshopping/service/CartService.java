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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    public CartService(CartRepository cartRepository,
                       CartItemRepository cartItemRepository,
                       ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Cart getOrCreateCart(User user) {
        return cartRepository.findByUser(user).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setUser(user);
            return cartRepository.save(cart);
        });
    }

    @Transactional
    public CartResponse getCart(User user) {
        return CartResponse.from(getOrCreateCart(user));
    }

    @Transactional
    public CartResponse addItem(User user, AddCartItemRequest request) {
        Cart cart = getOrCreateCart(user);
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + request.productId()));

        CartItem existing = cart.getItems().stream()
                .filter(i -> i.getProduct().getId().equals(product.getId()))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + request.quantity());
        } else {
            CartItem item = new CartItem();
            item.setCart(cart);
            item.setProduct(product);
            item.setQuantity(request.quantity());
            cart.getItems().add(item);
        }
        return CartResponse.from(cartRepository.save(cart));
    }

    @Transactional
    public void removeItem(User user, Long itemId) {
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + itemId));
        if (!item.getCart().getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("Cart item does not belong to the current user");
        }
        Cart cart = item.getCart();
        cart.getItems().remove(item);
        cartRepository.save(cart);
    }

    @Transactional
    public void clearCart(User user) {
        cartRepository.findByUser(user).ifPresent(cart -> {
            cart.getItems().clear();
            cartRepository.save(cart);
        });
    }
}
