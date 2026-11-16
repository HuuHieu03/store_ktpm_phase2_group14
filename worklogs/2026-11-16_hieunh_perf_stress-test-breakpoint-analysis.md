---
worklog_id: "WL-20261116-HIEUNH-001"
title: "Kiểm thử đo tải và xác định điểm gãy năng lực hệ thống (Capacity Limit / Breakpoint) trên Kaggle"
date: "2026-11-16"
author:
  name: "Nguyễn Hữu Hiệu"
  username: "HuuHieu03"
  student_id: "21020000"
  role: "Performance QA / Backend Architect"
time_spent: "4.0h"
phase: "Phase 2 - Architecture Analysis & Refactoring"
category: "perf"
status: "completed"
task_ref:
  issue: "#14"
  branch: "perf/kaggle-load-test"
  commits:
    - "60864d7"
tags:
  - "load-testing"
  - "stress-testing"
  - "capacity-limit"
  - "breakpoint-analysis"
  - "locust"
  - "kaggle"
---

# Báo cáo thực nghiệm: Đo tải đa tầng (Stress Ladder) và xác định giới hạn năng lực hệ thống

## 1. Tóm tắt nhanh (Executive Summary)

- **Vấn đề giải quyết:** Đánh giá giới hạn năng lực chịu tải tối đa (Capacity Limit) và điểm gãy hiệu năng (Performance Breakpoint / Knee) của hệ thống `single-store-service` ở trạng thái nguyên bản (AS-IS, commit `60864d7`) trên hạ tầng chuẩn hóa Kaggle CPU (4 vCPU, 31.35 GiB RAM, MySQL 8.0, OpenJDK 21) mà không can thiệp mã nguồn nghiệp vụ.
- **Kết quả đạt được:** Hoàn thành toàn diện bài đo tải đa tầng (Stress Ladder) từ 5 đến 500 người dùng ảo đồng thời (11 pha liên tục, tổng 82.048 requests, 0 lỗi HTTP). Xác định được thông lượng bão hòa cực đại đạt **207.04 RPS**. Điểm gãy độ trễ (Latency Breakpoint) bắt đầu xuất hiện rõ rệt từ mức **300 - 400 users** và suy thoái trầm trọng tại **500 users** (p50 vọt từ 8ms lên 180ms, p95 vọt từ 140ms lên 730ms, p99 chạm 1.100ms).
- **Tác động chính:** Cung cấp đầy đủ bằng chứng thực nghiệm (CSV, HTML, log) và khoanh vùng 3 điểm nghẽn kiến trúc cốt lõi làm cơ sở cho các quyết định tái cấu trúc trong Phase 2: nghẽn CPU do tính toán mã hóa BCrypt, nghẽn hàng đợi kết nối DB HikariCP (pool size mặc định 10), và thiếu vắng tầng bộ nhớ đệm (Cache-Aside) cho các truy vấn đọc danh mục sản phẩm.

---

## 2. Mục tiêu & Phạm vi (Objectives & Scope)

### Mục tiêu cụ thể:
- Thực thi kịch bản kiểm thử tải tự động không tương tác (headless batch) trên Kaggle thông qua Kaggle CLI (`kaggle kernels push/status/output`).
- Thiết lập kịch bản tăng tải bậc thang (Stress Ladder): 5 -> 10 -> 30 -> 60 -> 100 -> 150 -> 200 -> 250 -> 300 -> 400 -> 500 virtual users nhằm tìm ra ngưỡng suy thoái hiệu năng thực tế.
- Đo lường và đối chiếu thông lượng (RPS), phân vị độ trễ (p50, p95, p99), tỷ lệ lỗi của từng endpoint nghiệp vụ.
- Định vị chính xác các điểm nghẽn tài nguyên (CPU, Thread Pool, Connection Pool, Lock contention).

### Ngoài phạm vi (Out of Scope):
- Không chỉnh sửa mã nguồn nghiệp vụ (`src/main/`) trong phiên kiểm thử này.
- Không đo độ trễ mạng diện rộng (WAN/Internet) do bài test chạy khép kín trong container Kaggle (localhost).
- Chưa áp dụng các giải pháp tối ưu (Redis caching, Async Order processing, Read Replica) sang nhánh này.

---

## 3. Bối cảnh & Hiện trạng kiến trúc (Architectural Context & AS-IS Baseline)

### 3.1. Hiện trạng hệ thống (AS-IS):
- **Kiến trúc:** Monolithic Spring Boot 3.5.0, chạy trực tiếp trên JVM 21, kết nối cơ sở dữ liệu MySQL 8.0.
- **Cấu hình Thread & Connection Pool:**
  - Tomcat Embedded Server: Giới hạn mặc định 200 worker threads (`server.tomcat.threads.max=200`).
  - HikariCP Connection Pool: Không cấu hình tường minh, nhận giá trị mặc định là 10 connections (`maximumPoolSize=10`), `connectionTimeout=30000ms`.
  - Cấu hình JPA: `open-in-view: false`, `hibernate.ddl-auto: validate`.
- **Phân bổ tải nghiệp vụ trong `locustfile.py`:**
  - 60% `GET /api/products?page=0&size=20` (Đọc danh sách sản phẩm phân trang)
  - 15% `GET /api/products/{id}` (Đọc chi tiết sản phẩm)
  - 10% `POST /api/auth/login` (Xác thực người dùng, băm mật khẩu BCrypt)
  - 10% `GET /api/orders/me?page=0&size=20` (Truy vấn lịch sử đơn hàng cá nhân)
  - 5% `POST /api/orders` (Giao dịch ghi: khóa bi quan `SELECT ... FOR UPDATE`, trừ tồn kho, tạo đơn, ghi audit)
  - Thời gian chờ giữa các thao tác của mỗi người dùng ảo: 1 đến 3 giây (`between(1, 3)`).

### 3.2. Đánh giá chỉ số chất lượng kiến trúc (Quality Attributes):
| Thuộc tính (Attribute) | Baseline Pha 1 (60 users) | Giới hạn đo thực tế Pha 2 (500 users) | Nhận xét kiến trúc |
| :--- | :--- | :--- | :--- |
| **Max Clean Throughput** | 29.27 RPS | 207.04 RPS | Tăng 7.07x so với mức đo kiểm tra Phase 1 |
| **Median Latency (p50)** | 3 - 8 ms | 180 ms | Tăng 22.5x khi tải tăng từ 300 lên 500 users |
| **Tail Latency (p95)** | 82 - 91 ms | 730 ms | Suy thoái rõ rệt do nghẽn CPU và hàng đợi kết nối |
| **Tail Latency (p99)** | 91 - 100 ms | 1.100 ms | Vượt ngưỡng 1 giây ở mức 500 users |
| **Tỷ lệ lỗi (Error Rate)** | 0% | 0% | Hệ thống giữ vững tính toàn vẹn, không crash hay rò rỉ kết nối |

---

## 4. Chi tiết thực nghiệm & Kết quả đo đạc (Detailed Experimental Results)

### 4.1. Thông số môi trường đo kiểm
- **Nền tảng:** Kaggle CPU Notebook (`KernelWorkerStatus.COMPLETE`)
- **Tài nguyên phần cứng:** 4 vCPU Intel Xeon, 31.35 GiB RAM
- **Hệ điều hành:** Ubuntu 24.04 LTS (Kernel Linux 6.6)
- **Môi trường thực thi:** OpenJDK 21.0.13, MySQL 8.0.46, Locust 2.32.1 (Python 3.13.18)
- **Run ID lưu trữ:** `results/20261116T145327Z` (Gồm 70 tệp dữ liệu chi tiết)
- **Git Commit SHA kiểm thử:** `60864d7430b5d9f93e71e33d9e92244dbbbceb97` (Nhánh `perf/kaggle-load-test`, base từ `main`)

### 4.2. Bảng kết quả tổng hợp qua 11 pha tăng tải

| Pha (Phase) | Users | Thời gian (s) | Tổng Requests | Thất bại | Lỗi (%) | Throughput (RPS) | p50 (ms) | p95 (ms) | p99 (ms) |
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

### 4.3. Biểu đồ diễn tiến độ trễ và thông lượng (Latency & RPS Progression)

```text
RPS (Throughput) Progression:
 5u:  [##] 2.46 RPS
10u:  [#####] 4.96 RPS
30u:  [##############] 14.63 RPS
60u:  [#############################] 28.58 RPS
100u: [###############################################] 47.47 RPS
150u: [#######################################################################] 71.02 RPS
200u: [##############################################################################################] 94.51 RPS
250u: [#######################################################################################################################] 118.96 RPS
300u: [######################################################################################################################################] 142.12 RPS
400u: [######################################################################################################################################################################] 184.55 RPS
500u: [####################################################################################################################################################################################] 207.04 RPS (Plateau reached)

Latency p50 vs p95 (ms):
 5u:  p50=10ms  | p95=110ms
30u:  p50=6ms   | p95=100ms
100u: p50=6ms   | p95=100ms
200u: p50=6ms   | p95=110ms
300u: p50=8ms   | p95=140ms  <--- Ngưỡng an toàn cuối cùng
400u: p50=29ms  | p95=240ms  <--- Bắt đầu xuất hiện suy thoái độ trễ
500u: p50=180ms | p95=730ms  <--- Bão hòa tài nguyên, suy thoái nghiêm trọng (p99=1100ms)
```

### 4.4. Phân tích chi tiết theo từng Endpoint tại mức tải cực đại (500 Users)

Trích xuất từ `stress_500_stats.csv`:

| Endpoint | Requests | Thất bại | Throughput (RPS) | Median (p50) | Average | p95 | p99 | Max Latency |
| :--- | ---:| ---:| ---:| ---:| ---:| ---:| ---:| ---:|
| `POST /api/auth/login` | 1.821 | 0 | 20.05 | **570 ms** | 638.4 ms | **1.200 ms** | **1.500 ms** | 1.810 ms |
| `GET /api/orders/me` | 1.826 | 0 | 20.11 | **210 ms** | 260.0 ms | 710 ms | 900 ms | 1.197 ms |
| `POST /api/orders` | 905 | 0 | 9.97 | **210 ms** | 260.0 ms | 650 ms | 840 ms | 1.258 ms |
| `GET /api/products` | 11.380 | 0 | 125.32 | **150 ms** | 202.1 ms | 550 ms | 810 ms | 1.331 ms |
| `GET /api/products/{id}` | 2.868 | 0 | 31.58 | **150 ms** | 194.3 ms | 530 ms | 740 ms | 1.313 ms |
| **Aggregated (Toàn hệ thống)** | **18.800** | **0** | **207.04** | **180 ms** | **251.6 ms** | **730 ms** | **1.100 ms** | **1.810 ms** |

---

## 5. Phân tích Điểm nghẽn Kiến trúc (Architectural Bottleneck Root Causes)

Qua các số liệu đo đạc thực nghiệm, nhóm xác định 3 nguyên nhân cốt lõi dẫn tới hiện tượng bão hòa thông lượng ở mức ~207 RPS và bùng nổ độ trễ ở mức 500 users:

### 5.1. Điểm nghẽn 1: Chi phí CPU từ thuật toán băm mật khẩu BCrypt (`POST /api/auth/login`)
- **Biểu hiện:** Ở 500 users, endpoint login chiếm 10% lưu lượng (20 RPS) nhưng độ trễ trung vị vọt lên **570 ms** và p95 lên tới **1.200 ms**.
- **Nguyên nhân kỹ thuật:** Thuật toán BCrypt với cost factor mặc định (10 rounds) được thiết kế có chủ đích nhằm tiêu tốn tài nguyên CPU (~50-80ms CPU liên tục cho mỗi phép kiểm tra `BCrypt.checkpw`). Khi có 20 logins/giây chạy đồng thời trên máy chủ 4 vCPU:
  $$\text{Tải CPU}_{\text{login}} = 20 \times 70\text{ms} = 1.400\text{ms CPU/giây} \approx 1.4 \text{ full vCPUs}$$
  Chiếm hơn 35% tổng công suất CPU của toàn bộ máy chủ chỉ riêng cho việc hash mật khẩu. Điều này làm cạn kiệt CPU khả dụng cho các worker threads xử lý Servlet và tiến trình MySQL.

### 5.2. Điểm nghẽn 2: Hàng đợi chờ kết nối HikariCP (Connection Pool Starvation)
- **Biểu hiện:** Tại pha 500 users, độ trễ trung vị của `GET /api/orders/me` và `POST /api/orders` tăng vọt từ ~20-30ms lên **210 ms**.
- **Nguyên nhân kỹ thuật:** Cấu hình `application-mysql.yml` hiện tại không khai báo `maximum-pool-size`, do đó HikariCP nhận giá trị mặc định là **10 connections**. Trong khi đó, Tomcat có thể mở tới 200 worker threads. Khi đồng thời có hơn 100 requests cần truy vấn database, các luồng còn lại bị block và phải xếp hàng chờ trong hàng đợi kết nối của HikariDataSource.

### 5.3. Điểm nghẽn 3: Truy vấn cơ sở dữ liệu trực tiếp không có bộ đệm (No Caching on Read Hotspots)
- **Biểu hiện:** `GET /api/products` và `GET /api/products/{id}` chiếm tới 75% tổng lưu lượng hệ thống (~157 RPS tại 500 users). Mặc dù là các truy vấn đọc dữ liệu ít thay đổi, 100% request đều chạm thẳng vào database engine của MySQL qua Hibernate, gây áp lực I/O bộ nhớ và tranh chấp CPU tại MySQL daemon.

---

## 6. Kết luận & Định hướng Tối ưu hóa Phase 2 (Next Steps)

1. **Kết luận về Năng lực hệ thống hiện tại:**
   - **Vùng tải an toàn (SLA tối ưu):** $\le 300$ virtual users (Throughput $\le 142$ RPS, p50 $< 10$ ms, p95 $< 140$ ms, p99 $< 250$ ms).
   - **Ngưỡng suy thoái (Knee Point):** Xuất hiện giữa khoảng $300$ và $400$ virtual users.
   - **Giới hạn bão hòa (Capacity Limit):** $400 - 500$ virtual users, thông lượng kịch trần đạt xấp xỉ **207 RPS**. Vượt qua ngưỡng này, độ trễ đuôi (p99) vượt quá 1 giây, hệ thống không thể scale tuyến tính nếu không tối ưu kiến trúc.

2. **Kế hoạch hành động kiến trúc (Architectural Refactoring Plan):**
   - **Tối ưu 1 (Cache Layer):** Triển khai Cache-Aside Pattern với Redis (hoặc Caffeine in-memory) cho các endpoint tra cứu sản phẩm (`/api/products`), giải phóng ~75% truy vấn đọc khỏi MySQL.
   - **Tối ưu 2 (Connection Tuning):** Tinh chỉnh cấu hình HikariCP `maximum-pool-size` phù hợp với số lượng CPU cores theo công thức chuẩn của PostgreSQL/HikariCP: $\text{connections} = (\text{core\_count} \times 2) + \text{effective\_spindle\_count}$.
   - **Tối ưu 3 (Token & Auth Optimization):** Khuyến khích client tái sử dụng JWT Token đã phát hành thay vì liên tục gọi login trong chu kỳ ngắn, hoặc offload việc băm mật khẩu / áp dụng rate limiting nghiêm ngặt cho login endpoint.

---

## 7. Tài liệu & Tệp minh chứng tham chiếu (References)

- **Thư mục minh chứng gốc:** `performance/results/20261116T145327Z/`
- **Báo cáo tổng kết hiệu năng:** `performance/REPORT.md`
- **Kaggle Kernel Run URL:** https://www.kaggle.com/code/tomandjerry223/store-ktpm-load-test
- **Các tệp số liệu:** `summary.csv`, `stress_500_stats.csv`, `stress_300_stats.csv`, `stress_500_report.html`, `api.log`
