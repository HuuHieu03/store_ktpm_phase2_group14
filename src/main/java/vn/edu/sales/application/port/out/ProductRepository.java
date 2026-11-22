package vn.edu.sales.application.port.out;

import vn.edu.sales.domain.model.Product;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public interface ProductRepository {
    Product save(Product product);

    List<Product> findAllActive();

    ProductPage searchActive(String query, int page, int size);

    Optional<Product> findById(Long id);

    List<Product> findAllByIds(List<Long> ids);

    List<Product> findAllByIdsForUpdate(List<Long> ids);

    /** Atomically subtracts quantity from an ACTIVE product; returns the new stock, or empty if stock is short. */
    OptionalInt decreaseStock(Long productId, int quantity);

    /** Atomically adds quantity back to a product in any status; returns the new stock, or empty if it would overflow. */
    OptionalInt increaseStock(Long productId, int quantity);

    List<Product> saveAll(List<Product> products);

    boolean existsBySku(String sku);
}
