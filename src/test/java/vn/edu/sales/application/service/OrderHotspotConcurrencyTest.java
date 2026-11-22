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

// CONC-01.1: many buyers hit one SKU at once. Must pass before and after the CONC-01 stock-update refactor.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:conc01-hotspot;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=15000",
        "spring.datasource.hikari.maximum-pool-size=32",
        "spring.jpa.hibernate.ddl-auto=create-drop", "app.seed.enabled=false"
})
@TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
class OrderHotspotConcurrencyTest {
    private enum Outcome { SOLD, OUT_OF_STOCK }

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

        List<Outcome> outcomes = race(emails, ordersPerBuyer, hot.id());

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

        List<Outcome> outcomes = race(emails, 1, hot.id());

        assertThat(outcomes.stream().filter(Outcome.SOLD::equals).count()).isEqualTo(initial);
        assertThat(outcomes.stream().filter(Outcome.OUT_OF_STOCK::equals).count()).isEqualTo(buyers - initial);
        assertThat(stock(hot.id())).isZero();
        assertSalesRecorded(hot.id(), initial);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE stock_quantity<0", Integer.class)).isZero();
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

    // One thread per buyer, all released together; any exception other than out-of-stock fails the test.
    private List<Outcome> race(List<String> emails, int ordersPerBuyer, Long productId) throws Exception {
        var executor = Executors.newFixedThreadPool(emails.size());
        var start = new CountDownLatch(1);
        try {
            List<Future<List<Outcome>>> futures = new ArrayList<>();
            for (String email : emails) {
                Callable<List<Outcome>> buyer = () -> {
                    start.await();
                    List<Outcome> mine = new ArrayList<>();
                    for (int i = 0; i < ordersPerBuyer; i++) mine.add(buy(email, productId));
                    return mine;
                };
                futures.add(executor.submit(buyer));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<List<Outcome>> future : futures) outcomes.addAll(future.get(60, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome buy(String email, Long productId) {
        try {
            orders.create(email, "CONC01", "0900000000", "Demo address",
                    List.of(new OrderService.CreateLine(productId, 1)));
            return Outcome.SOLD;
        } catch (BusinessConflictException outOfStock) {
            return Outcome.OUT_OF_STOCK;
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
