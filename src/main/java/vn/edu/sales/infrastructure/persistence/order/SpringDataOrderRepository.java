package vn.edu.sales.infrastructure.persistence.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.sales.domain.model.OrderStatus;

import java.util.List;
import java.util.Optional;

public interface SpringDataOrderRepository extends JpaRepository<OrderJpaEntity, Long> {
    @EntityGraph(attributePaths = "items")
    Optional<OrderJpaEntity> findWithItemsById(Long id);

    @EntityGraph(attributePaths = "items")
    List<OrderJpaEntity> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = "items")
    List<OrderJpaEntity> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "items")
    List<OrderJpaEntity> findAllByStatusOrderByCreatedAtDesc(OrderStatus status);

    // No join fetch: with "distinct ... join fetch" Hibernate on H2 reads the row first and locks it in a
    // follow-on query, so a waiting cancel still saw PENDING and restored stock twice. Items load lazily
    // after the lock, inside the same transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderJpaEntity o where o.id = :id")
    Optional<OrderJpaEntity> findByIdForUpdate(@Param("id") Long id);
}
