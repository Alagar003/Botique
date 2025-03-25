

package com.example.Boutique_Final.service;
import com.example.Boutique_Final.Mapper.CartMapper;
import com.example.Boutique_Final.dto.CartDTO;
import com.example.Boutique_Final.exception.ResourceNotFoundException;
import com.example.Boutique_Final.exception.UserNotFoundException;
import com.example.Boutique_Final.model.Cart;
import com.example.Boutique_Final.model.CartItem;
import com.example.Boutique_Final.model.Product;
import com.example.Boutique_Final.model.User;
import com.example.Boutique_Final.repositories.CartRepository;
import com.example.Boutique_Final.repositories.ProductRepository;
import com.example.Boutique_Final.repositories.UserRepository;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class CartService {
    private static final Logger logger = LoggerFactory.getLogger(CartService.class);

    private final CartRepository cartRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final CartMapper cartMapper;

    public CartService(CartRepository cartRepository, UserRepository userRepository,
                       ProductRepository productRepository, CartMapper cartMapper) {
        this.cartRepository = cartRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.cartMapper = cartMapper;
    }

    public CartDTO getOrCreateCart(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found for email: " + email));
        return getOrCreateCart(user);
    }

    private CartDTO getOrCreateCart(User user) {
        Cart cart = cartRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setUserId(user.getId());
                    newCart.setItems(List.of());
                    return cartRepository.save(newCart);
                });

        return cartMapper.toDTO(cart);
    }





    private Cart createNewCartForUser (String userId) {
        // Fetch the user from the database using the userId
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User  not found for ID: " + userId));

        // Create a new cart and set the user
        Cart newCart = new Cart();
        newCart.setUser (user); // Set the User object, not just the userId

        return cartRepository.save(newCart); // Save the new cart to the database
    }

    public CartDTO addToCart(String userId, String productId, int quantity) {
        // Validate quantity
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero.");
        }

        // Convert userId to ObjectId
        ObjectId userObjectId = new ObjectId(userId);

        // Fetch the product to check stock
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + productId));

        // Check if enough stock is available
        if (product.getQuantity() < quantity) {
            throw new IllegalArgumentException("Not enough stock available for product ID: " + productId);
        }

        // Find or create a cart for the user
        Cart cart = cartRepository.findByUserId(userObjectId)
                .orElseGet(() -> createNewCartForUser (userObjectId));

        // Add product to cart
        cart.addItem(product, quantity);

        // Decrement stock
        product.setQuantity(product.getQuantity() - quantity);
        productRepository.save(product); // Save updated product stock

        // Save updated cart
        cartRepository.save(cart);

        return cartMapper.toDTO(cart); // Return the updated cart as DTO
    }



    // Method to create a new cart for the user
    private Cart createNewCartForUser(ObjectId userId) {
        User user = userRepository.findById(userId.toHexString())
                .orElseThrow(() -> new ResourceNotFoundException("User not found for ID: " + userId));

        Cart newCart = new Cart();
        newCart.setUser(user); // Set User object
        newCart.setUserId(userId); // Ensure userId is saved

        return cartRepository.save(newCart);
    }







    public void clearCart(String userId) {
        ObjectId userObjectId = new ObjectId(userId);
        cartRepository.deleteByUserId(userObjectId);
    }


    public Optional<Cart> getCartByUserId(String userId) {
        try {
            ObjectId objectId = new ObjectId(userId);
            return cartRepository.findByUserId(objectId);
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // Handle invalid userId
        }
    }



    @Autowired
    private MongoTemplate mongoTemplate;



    public CartDTO updateItemQuantity(String userId, String productId, String action) {
        ObjectId userObjectId;
        try {
            userObjectId = new ObjectId(userId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("❌ Invalid userId format: " + userId);
        }

        if (!ObjectId.isValid(productId)) {
            throw new IllegalArgumentException("❌ Invalid productId format: " + productId);
        }
        ObjectId productObjectId = new ObjectId(productId);

        // Query the cart using userId as ObjectId
        Query cartQuery = new Query(Criteria.where("userId").is(userObjectId));
        Cart existingCart = mongoTemplate.findOne(cartQuery, Cart.class, "cart");

        if (existingCart == null) {
            throw new ResourceNotFoundException("🛑 No cart found for userId: " + userId);
        }

        // Check if product exists in the cart
        Optional<CartItem> cartItemOptional = existingCart.getItems().stream()
                .filter(item -> item.getProduct().getId().equals(productObjectId))
                .findFirst();

        if (cartItemOptional.isEmpty()) {
            throw new ResourceNotFoundException("❌ Product ID " + productId + " not found in cart");
        }

        CartItem cartItem = cartItemOptional.get();
        int currentQuantity = cartItem.getQuantity();

        // Update quantity based on action
        if ("increase".equals(action)) {
            cartItem.setQuantity(currentQuantity + 1);
            // Decrease stock
            Product product = cartItem.getProduct();
            product.setQuantity(product.getQuantity() - 1);
            productRepository.save(product); // Save updated product stock
        } else if ("decrease".equals(action)) {
            if (currentQuantity > 1) {
                cartItem.setQuantity(currentQuantity - 1);
                // Increase stock
                Product product = cartItem.getProduct();
                product.setQuantity(product.getQuantity() + 1);
                productRepository.save(product); // Save updated product stock
            } else {
                throw new IllegalArgumentException("Quantity cannot be less than 1.");
            }
        } else {
            throw new IllegalArgumentException("Invalid action: " + action);
        }

        // Save updated cart
        cartRepository.save(existingCart);
        return new CartDTO(existingCart, userRepository); // Use the constructor that takes Cart and UserRepository
    }




    public boolean removeProductFromCart(String email, String productId) {
        // Find user by email
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            logger.error("User  not found with email: {}", email);
            return false;
        }

        // Fetch cart using user ID
        Optional<Cart> cartOptional = cartRepository.findByUserId(user.getId());
        if (cartOptional.isEmpty()) {
            logger.error("Cart not found for user with ID: {}", user.getId());
            return false; // Cart not found
        }

        Cart cart = cartOptional.get();
        logger.info("Cart fetched for user {}: {}", user.getId(), cart.getItems().size());

        // Convert productId to ObjectId
        ObjectId productObjectId;
        try {
            productObjectId = new ObjectId(productId);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid product ID format: {}", productId);
            return false; // Invalid product ID format
        }

        // Remove product from cart and update stock
        boolean removed = cart.getItems().removeIf(cartItem -> {
            ObjectId cartProductId = cartItem.getProduct().getId();
            if (cartProductId != null && cartProductId.equals(productObjectId)) {
                // Increase stock of the product
                Product product = cartItem.getProduct();
                product.setQuantity(product.getQuantity() + cartItem.getQuantity()); // Restore stock
                productRepository.save(product); // Save updated product stock
                return true; // Remove the item from the cart
            }
            return false;
        });

        if (!removed) {
            logger.warn("Product with ID {} not found in cart.", productId);
            return false; // Product not found in cart
        }

        cartRepository.save(cart); // Save updated cart
        logger.info("Product removed from cart successfully.");
        return true;
    }


}
