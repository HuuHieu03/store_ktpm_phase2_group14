package vn.edu.sales.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import vn.edu.sales.application.port.out.ProductRepository;
import vn.edu.sales.application.port.out.UserRepository;
import vn.edu.sales.application.services.OrderService;
import vn.edu.sales.domain.exception.BusinessConflictException;
import vn.edu.sales.domain.model.Order;
import vn.edu.sales.domain.model.Product;
import vn.edu.sales.domain.model.ProductStatus;
import vn.edu.sales.domain.model.Role;
import vn.edu.sales.domain.model.User;
import vn.edu.sales.domain.model.UserStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// CONC-01: many buyers (and cancellations) hit one SKU at once; stock and its movement log must stay exact.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:conc01-hotspot;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=15000",
        "spring.datasource.hikari.maximum-pool-size=32",
        "spring.jpa.hibernate.ddl-auto=create-drop", "app.seed.enabled=false"
})
@TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
class OrderHotspotConcurrencyTest {
    private enum Outcome { SOLD, OUT_OF_STOCK, CANCELLED, ALREADY_CANCELLED }

    @Autowired OrderService orders;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        for (String table : List.of("inventory_movements", "cart_items", "order_items", "orders", "products", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void enoughStockEveryConcurrentOrderSellsExactlyOnce() throws Exception {
        int buyers = 32, ordersPerBuyer = 5, initial = 1000, sold = buyers * ordersPerBuyer;
        Product hot = product("CONC01-HOT", initial);
        List<String> emails = customers(buyers);

        List<Outcome> outcomes = race(emails.stream().<Callable<List<Outcome>>>map(email -> () -> {
            List<Outcome> mine = new ArrayList<>();
            for (int i = 0; i < ordersPerBuyer; i++) mine.add(buy(email, hot.id()));
            return mine;
        }).toList()).stream().flatMap(List::stream).toList();

        assertThat(outcomes).hasSize(sold).containsOnly(Outcome.SOLD);
        assertThat(stock(hot.id())).isEqualTo(initial - sold);
        assertSalesRecorded(hot.id(), sold);
        // A lost update shows up as two orders that read the same stock level.
        List<Integer> before = jdbc.queryForList(
                "SELECT stock_before FROM inventory_movements WHERE product_id=? ORDER BY stock_before DESC",
                Integer.class, hot.id());
        assertThat(before).containsExactlyElementsOf(
                IntStream.iterate(initial, level -> level - 1).limit(sold).boxed().toList());
    }

    @RepeatedTest(3)
    void moreBuyersThanStockNeverOversells() throws Exception {
        int buyers = 50, initial = 20;
        Product hot = product("CONC01-OVERSELL", initial);
        List<String> emails = customers(buyers);

        List<Outcome> outcomes = race(emails.stream().<Callable<Outcome>>map(email -> () -> buy(email, hot.id())).toList());

        assertThat(outcomes.stream().filter(Outcome.SOLD::equals).count()).isEqualTo(initial);
        assertThat(outcomes.stream().filter(Outcome.OUT_OF_STOCK::equals).count()).isEqualTo(buyers - initial);
        assertThat(stock(hot.id())).isZero();
        assertSalesRecorded(hot.id(), initial);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE stock_quantity<0", Integer.class)).isZero();
    }

    @Test
    void concurrentCancelsAndPurchasesKeepStockAndMovementChainExact() throws Exception {
        int ordered = 40, lateBuyers = 30, initial = 100;
        Product hot = product("CONC01-CANCEL", initial);
        List<String> emails = customers(ordered + lateBuyers);
        List<String> owners = emails.subList(0, ordered);
        List<Long> orderIds = owners.stream().map(email -> create(email, hot.id()).id()).toList();

        // Every order is cancelled twice at once while new buyers keep buying the same SKU.
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < ordered; i++) {
            String owner = owners.get(i);
            Long orderId = orderIds.get(i);
            tasks.add(() -> cancel(owner, orderId));
            tasks.add(() -> cancel(owner, orderId));
        }
        emails.subList(ordered, emails.size()).forEach(email -> tasks.add(() -> buy(email, hot.id())));
        List<Outcome> outcomes = race(tasks);

        assertThat(outcomes.stream().filter(Outcome.CANCELLED::equals).count()).isEqualTo(ordered);
        assertThat(outcomes.stream().filter(Outcome.ALREADY_CANCELLED::equals).count()).isEqualTo(ordered);
        assertThat(outcomes.stream().filter(Outcome.SOLD::equals).count()).isEqualTo(lateBuyers);
        assertThat(stock(hot.id())).isEqualTo(initial - ordered + ordered - lateBuyers);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status='CANCELLED'", Integer.class))
                .isEqualTo(ordered);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT order_id) FROM inventory_movements WHERE movement_type='SALE_REVERSAL'",
                Integer.class)).isEqualTo(ordered);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE movement_type='SALE_REVERSAL'",
                Integer.class)).isEqualTo(ordered);
        // Movements are written while the row is locked, so in id order each one starts where the last ended.
        List<int[]> chain = jdbc.query("SELECT stock_before, stock_after FROM inventory_movements WHERE product_id=? ORDER BY id",
                (row, n) -> new int[]{row.getInt(1), row.getInt(2)}, hot.id());
        assertThat(chain).hasSize(ordered + ordered + lateBuyers);
        assertThat(chain.getFirst()[0]).isEqualTo(initial);
        for (int i = 1; i < chain.size(); i++) {
            assertThat(chain.get(i)[0]).as("movement %d starts at previous stock_after", i).isEqualTo(chain.get(i - 1)[1]);
        }
        assertThat(chain.getLast()[1]).isEqualTo(stock(hot.id()));
    }

    private Product product(String sku, int stock) {
        return products.save(new Product(null, sku, sku, "", new BigDecimal("120000"),
                stock, ProductStatus.ACTIVE, null));
    }

    private List<String> customers(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            String email = "conc01-" + i + "@example.test";
            users.save(new User(null, email, "test-only-hash", "CONC01", Role.CUSTOMER, UserStatus.ACTIVE, null));
            return email;
        }).toList();
    }

    // One thread per task, all released together; any exception other than the expected conflicts fails the test.
    private <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        var start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> { start.await(); return task.call(); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Order create(String email, Long productId) {
        return orders.create(email, "CONC01", "0900000000", "Demo address",
                List.of(new OrderService.CreateLine(productId, 1)));
    }

    private Outcome buy(String email, Long productId) {
        try {
            create(email, productId);
            return Outcome.SOLD;
        } catch (BusinessConflictException outOfStock) {
            return Outcome.OUT_OF_STOCK;
        }
    }

    private Outcome cancel(String email, Long orderId) {
        try {
            orders.cancelMine(email, orderId);
            return Outcome.CANCELLED;
        } catch (BusinessConflictException notPending) {
            return Outcome.ALREADY_CANCELLED;
        }
    }

    private void assertSalesRecorded(Long productId, int sold) {
        assertThat(count("orders")).isEqualTo(sold);
        assertThat(count("order_items")).isEqualTo(sold);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE product_id=? AND movement_type='SALE' AND quantity_change=-1 AND stock_after=stock_before-1",
                Integer.class, productId)).isEqualTo(sold);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity_change), 0) FROM inventory_movements WHERE product_id=?",
                Integer.class, productId)).isEqualTo(-sold);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private int stock(Long id) {
        return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, id);
    }
}
