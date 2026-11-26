package vn.edu.sales.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.edu.sales.domain.exception.ServiceBusyException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BcryptPasswordHasherBulkheadTest {

    @Test
    void limitsConcurrentPasswordOperationsToMaxConcurrent() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        CountDownLatch twoInside = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);

        PasswordEncoder encoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "hash";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                int current = active.incrementAndGet();
                maxObserved.accumulateAndGet(current, Math::max);
                twoInside.countDown();
                try {
                    assertTrue(releaseWorkers.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
                return true;
            }
        };

        var hasher = new BcryptPasswordHasher(encoder, 2, Duration.ofSeconds(5));
        try (var pool = Executors.newFixedThreadPool(4)) {
            Future<Boolean> f1 = pool.submit(() -> hasher.matches("secret", "hash"));
            Future<Boolean> f2 = pool.submit(() -> hasher.matches("secret", "hash"));
            assertTrue(twoInside.await(5, TimeUnit.SECONDS));

            Future<Boolean> f3 = pool.submit(() -> hasher.matches("secret", "hash"));
            Future<Boolean> f4 = pool.submit(() -> hasher.matches("secret", "hash"));
            Thread.sleep(50);
            assertEquals(2, maxObserved.get());

            releaseWorkers.countDown();
            assertTrue(f1.get(5, TimeUnit.SECONDS));
            assertTrue(f2.get(5, TimeUnit.SECONDS));
            assertTrue(f3.get(5, TimeUnit.SECONDS));
            assertTrue(f4.get(5, TimeUnit.SECONDS));
        }

        assertEquals(2, maxObserved.get());
        assertEquals(4L, hasher.bulkheadStats().acquiredCount());
        assertEquals(0L, hasher.bulkheadStats().rejectedCount());
    }

    @Test
    void rejectsWithServiceBusyWhenAcquireTimeoutExpires() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        PasswordEncoder encoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                inside.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "hash";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };

        var hasher = new BcryptPasswordHasher(encoder, 1, Duration.ofMillis(30));
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> holder = pool.submit(() -> hasher.hash("secret"));
            assertTrue(inside.await(5, TimeUnit.SECONDS));

            assertThrows(ServiceBusyException.class, () -> hasher.matches("secret", "hash"));
            release.countDown();
            assertEquals("hash", holder.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1L, hasher.bulkheadStats().acquiredCount());
        assertEquals(1L, hasher.bulkheadStats().rejectedCount());
    }

    @Test
    void releasesPermitWhenEncoderThrows() {
        PasswordEncoder failingEncoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return true;
            }
        };

        var hasher = new BcryptPasswordHasher(failingEncoder, 1, Duration.ofMillis(100));
        assertThrows(IllegalStateException.class, () -> hasher.hash("secret"));
        assertTrue(hasher.matches("secret", "hash"));
        assertEquals(2L, hasher.bulkheadStats().acquiredCount());
    }
}
