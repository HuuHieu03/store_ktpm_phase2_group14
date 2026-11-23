package vn.edu.sales.infrastructure.persistence.product;

import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import vn.edu.sales.application.port.out.ProductPage;
import vn.edu.sales.application.port.out.ProductRepository;
import vn.edu.sales.domain.model.Product;
import vn.edu.sales.domain.model.ProductStatus;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class ProductRepositoryAdapter implements ProductRepository {
    private final SpringDataProductRepository repository;
    private final Cache<PageKey, ProductPage> pages;
    private final Cache<DetailKey, Product> details;
    private final AtomicLong generation = new AtomicLong();

    public ProductRepositoryAdapter(SpringDataProductRepository repository,
                                    @Value("${app.product-cache.ttl:30s}") Duration ttl,
                                    @Value("${app.product-cache.maximum-size:1000}") long maximumSize) {
        this.repository = repository;
        this.pages = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maximumSize).build();
        this.details = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maximumSize).build();
    }

    @Override
    public Product save(Product product) {
        ProductJpaEntity entity = new ProductJpaEntity(
                product.id(), product.sku(), product.name(), product.description(), product.price(),
                product.stockQuantity(), product.status(), product.version()
        );
        Product saved = toDomain(repository.saveAndFlush(entity));
        invalidateAfterCommit();
        return saved;
    }

    @Override
    public List<Product> findAllActive() {
        return repository.findAllByStatusOrderByIdDesc(ProductStatus.ACTIVE).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public ProductPage searchActive(String query, int page, int size) {
        String normalized = query == null ? "" : query.trim();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            PageKey key = new PageKey(generation.get(), normalized, page, size);
            return pages.get(key, ignored -> loadPage(normalized, page, size));
        }
        return loadPage(normalized, page, size);
    }

    private ProductPage loadPage(String normalized, int page, int size) {
        var result = repository.searchByStatus(ProductStatus.ACTIVE, normalized, PageRequest.of(page, size));
        return new ProductPage(result.getContent().stream().map(this::toDomain).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Override
    public Optional<Product> findById(Long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return Optional.ofNullable(details.get(new DetailKey(generation.get(), id),
                    ignored -> repository.findById(id).map(this::toDomain).orElse(null)));
        }
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Product> findAllByIds(List<Long> ids) {
        return repository.findAllById(ids).stream().map(this::toDomain).toList();
    }

    @Override
    public List<Product> findAllByIdsForUpdate(List<Long> ids) {
        return repository.findAllByIdsForUpdate(ids).stream().map(this::toDomain).toList();
    }

    @Override
    public OptionalInt decreaseStock(Long productId, int quantity) {
        return stockAfter(repository.decreaseStock(productId, quantity), productId);
    }

    @Override
    public OptionalInt increaseStock(Long productId, int quantity) {
        return stockAfter(repository.increaseStock(productId, quantity), productId);
    }

    private OptionalInt stockAfter(int updatedRows, Long productId) {
        if (updatedRows == 0) return OptionalInt.empty();
        invalidateAfterCommit();
        // Same transaction, so this reads our own update even while other buyers wait on the row.
        return OptionalInt.of(repository.stockQuantityOf(productId));
    }

    @Override
    public List<Product> saveAll(List<Product> products) {
        List<ProductJpaEntity> entities = products.stream().map(product -> new ProductJpaEntity(
                product.id(), product.sku(), product.name(), product.description(), product.price(),
                product.stockQuantity(), product.status(), product.version())).toList();
        List<Product> saved = repository.saveAll(entities).stream().map(this::toDomain).toList();
        invalidateAfterCommit();
        return saved;
    }

    @Override
    public boolean existsBySku(String sku) {
        return repository.existsBySku(sku);
    }

    private Product toDomain(ProductJpaEntity entity) {
        return new Product(
                entity.getId(), entity.getSku(), entity.getName(), entity.getDescription(), entity.getPrice(),
                entity.getStockQuantity(), entity.getStatus(), entity.getVersion()
        );
    }

    private void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(); }
            });
        } else {
            invalidate();
        }
    }

    private void invalidate() {
        generation.incrementAndGet();
        pages.invalidateAll();
        details.invalidateAll();
    }

    private record PageKey(long generation, String query, int page, int size) {}
    private record DetailKey(long generation, Long id) {}
}
