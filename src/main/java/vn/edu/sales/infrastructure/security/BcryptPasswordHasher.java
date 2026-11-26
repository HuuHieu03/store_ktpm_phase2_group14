package vn.edu.sales.infrastructure.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import vn.edu.sales.application.port.out.PasswordHasher;
import vn.edu.sales.domain.exception.ServiceBusyException;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
public class BcryptPasswordHasher implements PasswordHasher {
    private static final String BUSY_MESSAGE = "Hệ thống xác thực đang bận; vui lòng thử lại sau";

    private final PasswordEncoder passwordEncoder;
    private final Semaphore semaphore;
    private final Duration acquireTimeout;
    private final AtomicLong acquiredCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong totalWaitNanos = new AtomicLong();

    public BcryptPasswordHasher(PasswordEncoder passwordEncoder) {
        this(passwordEncoder, 3, Duration.ofSeconds(2));
    }

    @Autowired
    public BcryptPasswordHasher(
            PasswordEncoder passwordEncoder,
            @Value("${app.auth-bulkhead.max-concurrent:3}") int maxConcurrent,
            @Value("${app.auth-bulkhead.acquire-timeout:2s}") Duration acquireTimeout) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("app.auth-bulkhead.max-concurrent must be >= 1");
        }
        if (acquireTimeout == null || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("app.auth-bulkhead.acquire-timeout must be >= 0");
        }
        this.passwordEncoder = passwordEncoder;
        this.semaphore = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = acquireTimeout;
    }

    @Override
    public String hash(String rawPassword) {
        return executeWithBulkhead(() -> passwordEncoder.encode(rawPassword));
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return executeWithBulkhead(() -> passwordEncoder.matches(rawPassword, passwordHash));
    }

    public BulkheadStats bulkheadStats() {
        return new BulkheadStats(acquiredCount.get(), rejectedCount.get(), totalWaitNanos.get());
    }

    private <T> T executeWithBulkhead(Supplier<T> action) {
        long start = System.nanoTime();
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rejectedCount.incrementAndGet();
            throw new ServiceBusyException(BUSY_MESSAGE);
        }
        if (!acquired) {
            rejectedCount.incrementAndGet();
            throw new ServiceBusyException(BUSY_MESSAGE);
        }
        totalWaitNanos.addAndGet(System.nanoTime() - start);
        acquiredCount.incrementAndGet();
        try {
            return action.get();
        } finally {
            semaphore.release();
        }
    }

    public record BulkheadStats(long acquiredCount, long rejectedCount, long totalWaitNanos) {
    }
}
