package vn.edu.sales.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.edu.sales.domain.model.ProductStatus;
import vn.edu.sales.infrastructure.persistence.product.ProductJpaEntity;
import vn.edu.sales.infrastructure.persistence.product.ProductRepositoryAdapter;
import vn.edu.sales.infrastructure.persistence.product.SpringDataProductRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ProductCacheTest {
    @Test
    void cachesByQueryAndPageAndInvalidatesOnCommit() {
        var repository = mock(SpringDataProductRepository.class);
        when(repository.searchByStatus(any(), anyString(), any())).thenReturn(new PageImpl<>(List.of()));
        when(repository.saveAll(any())).thenReturn(List.of());
        var adapter = new ProductRepositoryAdapter(repository, Duration.ofMinutes(1), 100);

        adapter.searchActive("tea", 0, 20);
        adapter.searchActive("tea", 0, 20);
        adapter.searchActive("tea", 1, 20);
        adapter.searchActive("coffee", 0, 20);
        verify(repository, times(1)).searchByStatus(ProductStatus.ACTIVE, "tea", PageRequest.of(0, 20));
        verify(repository, times(3)).searchByStatus(any(), anyString(), any());
        assertEquals(1L, adapter.pagesStats().hitCount());

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            adapter.saveAll(List.of());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clear();
        }
        adapter.searchActive("tea", 0, 20);
        verify(repository, times(2)).searchByStatus(ProductStatus.ACTIVE, "tea", PageRequest.of(0, 20));
    }

    @Test
    void invalidatesDetailTargetedAndKeepsPagesUntilStockDepleted() {
        var repository = mock(SpringDataProductRepository.class);
        when(repository.searchByStatus(any(), anyString(), any())).thenReturn(new PageImpl<>(List.of()));
        when(repository.findById(1L)).thenReturn(Optional.of(
                new ProductJpaEntity(1L, "SKU-1", "Tea", "Green", new BigDecimal("10.00"), 5, ProductStatus.ACTIVE, 0L)));
        when(repository.findById(2L)).thenReturn(Optional.of(
                new ProductJpaEntity(2L, "SKU-2", "Coffee", "Black", new BigDecimal("20.00"), 10, ProductStatus.ACTIVE, 0L)));
        when(repository.decreaseStock(1L, 1)).thenReturn(1);
        when(repository.stockQuantityOf(1L)).thenReturn(4, 0);

        var adapter = new ProductRepositoryAdapter(repository, Duration.ofMinutes(1), Duration.ofMinutes(1), 100);

        adapter.searchActive("tea", 0, 20);
        adapter.findById(1L);
        adapter.findById(2L);
        adapter.findById(2L);
        assertEquals(1L, adapter.detailsStats().hitCount());

        // Stock still > 0: only product 1 detail is invalidated; product 2 detail and pages stay cached.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            adapter.decreaseStock(1L, 1);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clear();
        }

        adapter.searchActive("tea", 0, 20);
        adapter.findById(2L);
        adapter.findById(1L);
        verify(repository, times(1)).searchByStatus(ProductStatus.ACTIVE, "tea", PageRequest.of(0, 20));
        verify(repository, times(1)).findById(2L);
        verify(repository, times(2)).findById(1L);

        // Stock hits 0: both product 1 detail and pages are invalidated.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            adapter.decreaseStock(1L, 1);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clear();
        }

        adapter.searchActive("tea", 0, 20);
        verify(repository, times(2)).searchByStatus(ProductStatus.ACTIVE, "tea", PageRequest.of(0, 20));
    }
}
