# Báo cáo Kiểm thử Hiệu năng & Giới hạn Năng lực Hệ thống (Performance & Capacity Benchmark)

Tài liệu này tổng hợp toàn bộ các đợt thực nghiệm kiểm thử tải, đo lường năng lực và xác định điểm gãy hiệu năng (Breakpoint / Capacity Limit) của hệ thống `single-store-service` trên nền tảng chuẩn hóa Kaggle CPU.

---

## PHẦN 1: Báo cáo Thực nghiệm Đo tải Đa tầng & Xác định Giới hạn Năng lực (Phase 2)

### 1. Thông tin phiên kiểm thử (Test Session Metadata)
- **Mã đợt đo (Run ID):** `20261116T145327Z` (Ngày thực hiện: 16/11/2026)
- **Commit ứng dụng kiểm thử (AS-IS):** `60864d7430b5d9f93e71e33d9e92244dbbbceb97` (Nhánh `perf/kaggle-load-test`, base từ `main`)
- **Mục tiêu thực nghiệm:** Khảo sát năng lực chịu tải từ mức cơ sở đến mức cực hạn (Stress Ladder từ 5 đến 500 người dùng ảo đồng thời) để xác định thông lượng bão hòa (Saturation Throughput) và điểm gãy độ trễ (Latency Knee Point) của kiến trúc AS-IS mà không chỉnh sửa mã nguồn nghiệp vụ.
- **Môi trường thực thi:** Kaggle Linux CPU Notebook (4 vCPU, 31.35 GiB RAM, Ubuntu 24.04 LTS, OpenJDK 21.0.13, MySQL 8.0.46, Locust 2.32.1). API, MySQL và Locust chạy trong cùng container, giao tiếp qua `localhost:18080`.
- **Thư mục minh chứng:** [performance/results/20261116T145327Z](results/20261116T145327Z) (gồm 70 tệp CSV, HTML, log, metadata).

### 2. Kịch bản tải đa tầng (Stress Ladder Workload)
- **Tác vụ & Tỷ trọng:** 60% danh sách sản phẩm (`GET /api/products`), 15% chi tiết sản phẩm (`GET /api/products/{id}`), 10% đăng nhập (`POST /api/auth/login`), 10% đơn hàng cá nhân (`GET /api/orders/me`), 5% tạo đơn hàng (`POST /api/orders`).
- **Hành vi người dùng:** Mỗi user chờ ngẫu nhiên từ 1 đến 3 giây (`between(1, 3)`), tốc độ tăng tải `spawn_rate = max(5, users // 10)`.
- **Dữ liệu kiểm thử:** Chuẩn bị độc lập tài khoản và sản phẩm tồn kho 100.000 đơn vị cho từng user trước từng pha, không tính vào thống kê Locust.

### 3. Bảng số liệu tổng hợp qua 11 pha tăng tải

| Pha (Phase) | Users | Thời gian (s) | Requests | Thất bại | Lỗi (%) | Throughput (RPS) | p50 (ms) | p95 (ms) | p99 (ms) |
| :--- | ---:| ---:| ---:| ---:| ---:| ---:| ---:| ---:| ---:|
| `smoke` | 5 | 60 | 145 | 0 | 0.0% | 2.46 | 10.0 | 110.0 | 130.0 |
| `baseline` | 10 | 60 | 293 | 0 | 0.0% | 4.96 | 8.0 | 100.0 | 110.0 |
| `main` | 30 | 90 | 1.303 | 0 | 0.0% | 14.63 | 6.0 | 100.0 | 110.0 |
| `high` | 60 | 90 | 2.549 | 0 | 0.0% | 28.58 | 6.0 | 100.0 | 110.0 |
| `stress_100` | 100 | 90 | 4.235 | 0 | 0.0% | 47.47 | 6.0 | 100.0 | 130.0 |
| `stress_150` | 150 | 90 | 6.337 | 0 | 0.0% | 71.02 | 5.0 | 110.0 | 130.0 |
| `stress_200` | 200 | 90 | 8.444 | 0 | 0.0% | 94.51 | 6.0 | 110.0 | 160.0 |
| `stress_250` | 250 | 90 | 10.644 | 0 | 0.0% | 118.96 | 6.0 | 120.0 | 190.0 |
| `stress_300` | 300 | 90 | 12.744 | 0 | 0.0% | 142.12 | 8.0 | 140.0 | 250.0 |
| `stress_400` | 400 | 90 | 16.554 | 0 | 0.0% | 184.55 | 29.0 | 240.0 | 470.0 |
| `stress_500` | 500 | 90 | 18.800 | 0 | 0.0% | 207.04 | 180.0 | 730.0 | 1.100.0 |
| **Tổng lũy kế** | — | — | **82.048** | **0** | **0.0%** | — | — | — | — |

### 4. Đánh giá Giới hạn Năng lực & Phân vùng Hiệu năng (Capacity Profiling)

1. **Vùng tải ổn định / Tối ưu SLA (Healthy Zone: $\le 300$ users):**
   - Từ 5 đến 300 users, thông lượng tăng trưởng tuyến tính lý tưởng ($RPS \approx users \times 0.47$).
   - Độ trễ duy trì ở mức xuất sắc: p50 ổn định $5 - 8$ ms, p95 ổn định $100 - 140$ ms, p99 $\le 250$ ms.
   - Không có bất kỳ lỗi HTTP nào, hệ thống đáp ứng tốt mục tiêu trải nghiệm người dùng.

2. **Vùng điểm gãy uốn cong (Knee / Contention Zone: $300 - 400$ users):**
   - Tại mức 400 users, thông lượng đạt 184.55 RPS.
   - Bắt đầu xuất hiện hiện tượng tranh chấp tài nguyên rõ rệt: p50 tăng 3.6 lần (từ 8ms lên 29ms), p95 tăng từ 140ms lên 240ms, p99 tăng lên 470ms.

3. **Vùng suy thoái & Bão hòa cực hạn (Saturation / Degradation Zone: $400 - 500$ users):**
   - Tại mức 500 users, tốc độ tăng trưởng thông lượng phẳng lại rõ rệt (chỉ tăng từ 184.55 lên 207.04 RPS dù tải tăng thêm 100 users).
   - Độ trễ bùng nổ theo hàm mũ:
     - Median (p50) vọt lên **180 ms** (tăng 22.5 lần so với mức 300 users).
     - Tail latency (p95) vọt lên **730 ms** (tăng 5.2 lần).
     - Tail latency (p99) chạm ngưỡng **1.100 ms (1.1 giây)**.
   - **Giới hạn bão hòa thực tế (Capacity Limit):** Đạt ngưỡng trần **~207 RPS**. Hệ thống không phát sinh lỗi HTTP 5xx hay rò rỉ kết nối (0 failures / 82.048 requests), chứng minh cơ chế graceful handling của Tomcat/HikariCP hoạt động ổn định nhưng hàng đợi xử lý đã chạm trần năng lực của 4 vCPU.

### 5. Chi tiết hiệu năng từng Endpoint tại mức tải 500 Users

| Endpoint | Method | Requests | Throughput (RPS) | Median p50 (ms) | Average (ms) | p95 (ms) | p99 (ms) | Max (ms) |
| :--- | :--- | ---:| ---:| ---:| ---:| ---:| ---:| ---:|
| `/api/auth/login` | POST | 1.821 | 20.05 | **570.0** | 638.4 | **1.200.0** | **1.500.0** | 1.810.4 |
| `/api/orders/me` | GET | 1.826 | 20.11 | **210.0** | 260.0 | 710.0 | 900.0 | 1.197.5 |
| `/api/orders` | POST | 905 | 9.97 | **210.0** | 260.0 | 650.0 | 840.0 | 1.258.5 |
| `/api/products` | GET | 11.380 | 125.32 | **150.0** | 202.1 | 550.0 | 810.0 | 1.331.4 |
| `/api/products/{id}` | GET | 2.868 | 31.58 | **150.0** | 194.3 | 530.0 | 740.0 | 1.312.9 |
| **Toàn bộ hệ thống** | **All** | **18.800** | **207.04** | **180.0** | **251.6** | **730.0** | **1.100.0** | **1.810.4** |

### 6. Định vị 3 Điểm nghẽn Kiến trúc Cốt lõi (Identified Bottlenecks)

1. **Nghẽn CPU do băm mật khẩu BCrypt (`POST /api/auth/login`):**
   - Điểm nghẽn lớn nhất: p50 lên tới 570ms, p95 lên tới 1.200ms.
   - Thuật toán BCrypt (10 rounds) tiêu tốn 50-80ms CPU liên tục cho mỗi request. Với 20 login requests/giây, riêng tác vụ này chiếm dụng tương đương 1.4 vCPU (trên tổng 4 vCPU của máy chủ).
2. **Nghẽn hàng đợi kết nối cơ sở dữ liệu (HikariCP Starvation):**
   - `application-mysql.yml` dùng cấu hình mặc định `maximumPoolSize = 10`.
   - Với 200 worker threads của Tomcat đồng thời phục vụ 500 users ảo, việc chỉ có 10 kết nối MySQL khả dụng buộc các giao dịch tạo đơn và đọc đơn hàng phải xếp hàng chờ trong hàng đợi kết nối, đẩy median response time lên 210ms.
3. **Thiếu tầng bộ đệm (No Caching Layer) cho các truy vấn đọc:**
   - 75% lưu lượng hệ thống là truy vấn sản phẩm (`/api/products`), tất cả đều truy vấn trực tiếp vào MySQL qua Hibernate mà không qua Redis/In-memory cache.

---

## PHẦN 2: Báo cáo Nghiệm thu Baseline Pha 1 (Lưu trữ Đối chiếu)

### 1. Phiên bản và kết luận Pha 1
- **Commit ứng dụng đã đo:** `941ec24a8158a5bf072d823a129abff5415d0c30`.
- **Run Kaggle:** `20261021T023505Z`, ngày 21/10/2026; gồm 4 pha cơ bản 5/10/30/60 user.
- **Kết quả:** 13.943 request, 0 thất bại; đạt 29.27 RPS ở pha 60 users, p95 đạt 82 ms, p99 đạt 91 ms.
- **Thư mục minh chứng:** [performance/results/20261021T023505Z](results/20261021T023505Z).

### 2. Bảng đối chiếu số liệu Pha 1

| Pha | Users | Giây cấu hình | Requests | Thất bại | RPS | p50 ms | p95 ms | p99 ms |
| :--- | ---:| ---:| ---:| ---:| ---:| ---:| ---:| ---:|
| smoke | 5 | 60 | 145 | 0 | 2.46 | 8 | 87 | 100 |
| baseline | 10 | 120 | 596 | 0 | 5.01 | 5 | 85 | 90 |
| main | 30 | 300 | 4.437 | 0 | 14.82 | 3 | 82 | 88 |
| high | 60 | 300 | 8.765 | 0 | 29.27 | 3 | 82 | 91 |
| **Tổng** | — | — | **13.943** | **0** | — | — | — | — |

---

## PHẦN 3: Đối chiếu So sánh Pha 1 vs Pha 2

| Tiêu chí | Đợt đo Pha 1 (`20261021T023505Z`) | Đợt đo Pha 2 (`20261116T145327Z`) | Nhận xét đối chiếu |
| :--- | :--- | :--- | :--- |
| **Commit kiểm thử** | `941ec24` | `60864d7` | Mã nguồn nghiệp vụ AS-IS tương đương |
| **Số pha đo đạc** | 4 pha (5 -> 60 users) | 11 pha (5 -> 500 users) | Khảo sát sâu đến ngưỡng gãy |
| **Tổng số Requests** | 13.943 requests | 82.048 requests | Khối lượng kiểm thử tăng 5.88x |
| **Throughput đỉnh** | 29.27 RPS (ở 60 users) | 207.04 RPS (ở 500 users) | Tìm ra trần bão hòa thực tế |
| **Độ trễ p50** | 3 ms (ở 60 users) | 6 ms (ở 60 users) -> 180 ms (500 users) | Khảo sát được đường cong suy thoái |
| **Độ trễ p95** | 82 ms (ở 60 users) | 100 ms (ở 60 users) -> 730 ms (500 users) | Xác định điểm gãy từ 300-400 users |
| **Tỷ lệ lỗi (Error)** | 0.0% | 0.0% | Ổn định xuyên suốt toàn bài đo |

---

## PHẦN 4: Hotspot 1 SKU — CONC-01 trước/sau (TEST-00b, CONC-01.5)

Cùng runner `LOAD_SCENARIO=hotspot`, cùng loại máy Kaggle CPU (4 luồng, MySQL 8.0.46 cấu hình mặc định của Ubuntu). Mỗi phiên bản đo **một lượt**. Đơn/s = số đơn trong DB ÷ thời gian pha, lấy từ bảng toàn vẹn tồn kho trong từng REPORT.

| Pha | Trước: `FOR UPDATE` (`462fecf`, [20261121T153809Z](results/20261121T153809Z/REPORT.md)) | CONC-01.3 bị bác (`58af98f`, [20261122T090451Z](results/20261122T090451Z/REPORT.md)) | Sau: UPDATE nguyên tử (`a75256d`, [20261122T104048Z](results/20261122T104048Z/REPORT.md)) |
| :--- | :--- | :--- | :--- |
| hotspot_10 | 43,8 đơn/s · p95 290 ms | 21,0 đơn/s · 82% lỗi 409 | 42,7 đơn/s · p95 290 ms |
| hotspot_50 | 48,4 đơn/s · p95 1.100 ms | 24,8 đơn/s · 88% lỗi 409 | 43,1 đơn/s · p95 1.400 ms |
| hotspot_100 | 49,5 đơn/s · p95 2.300 ms | 26,2 đơn/s · 88% lỗi 409 | 46,9 đơn/s · p95 2.300 ms |
| hotspot_200 | 50,0 đơn/s · p95 4.300 ms | 24,5 đơn/s · 88% lỗi 409 | 48,5 đơn/s · p95 4.500 ms |
| Deadlock (tổng 4 pha) | 0 | 50.599 | 0 |
| Toàn vẹn tồn kho / oversell | OK · 300/300 | OK · 300/300 | OK · 300/300 |

Thời gian chờ khoá trung bình mỗi lần ở hotspot_10: 194 ms (trước) và 197 ms (sau). CPU mysqld 26–28% ở cả hai phiên; CPU API tương đương (hotspot_10: 98% so với 110%, các pha còn lại lệch ≤ 3 điểm %).

**Kết luận:**
- **UPDATE nguyên tử không tăng thông lượng cho 1 SKU nóng.** Sau CONC-01, số đơn/s thấp hơn 3–11% ở cả 4 pha. Với 1 lượt đo mỗi bên trên hai phiên Kaggle khác nhau, mức lệch này chưa đủ để kết luận là tệ hơn, nhưng chắc chắn là không cải thiện.
- **Trần ~48 đơn/s ≈ 21 ms giữ khoá mỗi đơn, nhiều khả năng phần lớn là chi phí commit (giả thuyết, chưa đo trực tiếp trên Kaggle).** Khoá dòng chỉ nhả *sau* commit, mà mỗi commit với `innodb_flush_log_at_trx_commit=1` và `sync_binlog=1` (mặc định) phải chờ ghi đĩa. Vì vậy các đơn cùng SKU xếp hàng qua từng lần fsync, khoá bằng `FOR UPDATE` hay `UPDATE` đều như nhau. Đo vi mô trên MySQL 8.4 bằng Docker (máy dev, không phải Kaggle), cùng chuỗi `UPDATE` dòng nóng + `INSERT` + `COMMIT` tuần tự: **8,9 ms/tx** khi có fsync mỗi commit, **0,16 ms/tx** khi tắt. Trên Kaggle chưa đo trực tiếp vì runner chưa ghi các biến này vào metadata.
- CONC-01 vẫn giữ được các lợi ích về tính đúng: bỏ đọc–sửa–ghi trên entity, sửa lỗi huỷ đơn hai lần (`58af98f`), ghim thứ tự khoá tránh deadlock khoá ngoại.
- Muốn vượt trần thì phải bỏ việc mỗi đơn chiếm riêng một lần commit trên cùng một dòng: chia tồn kho SKU nóng thành nhiều phần (nhiều commit chạy song song, InnoDB gộp được fsync), hoặc gom đơn theo lô qua hàng đợi (một commit cho nhiều đơn). Nới độ bền ghi đĩa chỉ nên dùng để kiểm chứng giả thuyết, không phải giải pháp cho dữ liệu đơn hàng. Phân tích đầy đủ trong [ADR CONC-01](../worklogs/2026-11-22_sonha_db_adr-atomic-stock-update.md).
