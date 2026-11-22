package vn.edu.sales.infrastructure.persistence.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.sales.domain.model.ProductStatus;

import java.util.List;

public interface SpringDataProductRepository extends JpaRepository<ProductJpaEntity, Long> {
    List<ProductJpaEntity> findAllByStatusOrderByIdDesc(ProductStatus status);

    @Query("select p from ProductJpaEntity p where p.status = :status and " +
            "(:query = '' or lower(p.name) like lower(concat('%', :query, '%')) or " +
            "lower(p.sku) like lower(concat('%', :query, '%'))) order by p.id desc")
    Page<ProductJpaEntity> searchByStatus(@Param("status") ProductStatus status,
                                          @Param("query") String query, Pageable pageable);

    boolean existsBySku(String sku);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id in :ids order by p.id")
    List<ProductJpaEntity> findAllByIdsForUpdate(@Param("ids") List<Long> ids);

    @Modifying
    @Query(value = "update products set stock_quantity = stock_quantity - :quantity, version = version + 1, " +
            "updated_at = current_timestamp where id = :id and status = 'ACTIVE' and stock_quantity >= :quantity",
            nativeQuery = true)
    int decreaseStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Query(value = "select stock_quantity from products where id = :id", nativeQuery = true)
    int stockQuantityOf(@Param("id") Long id);
}
