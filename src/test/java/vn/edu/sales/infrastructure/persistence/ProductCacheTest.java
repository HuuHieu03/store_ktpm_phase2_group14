package vn.edu.sales.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.edu.sales.domain.model.ProductStatus;
import vn.edu.sales.infrastructure.persistence.product.ProductRepositoryAdapter;
import vn.edu.sales.infrastructure.persistence.product.SpringDataProductRepository;

import java.time.Duration;
import java.util.List;

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
}
