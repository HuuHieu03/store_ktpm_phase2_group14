---
worklog_id: "WL-20261118-DAGOUSTIC-001"
title: "Triển khai cache Caffeine cho truy vấn sản phẩm"
date: "2026-11-18"
author:
  name: "Đăng"
  username: "dagoustic"
  student_id: "23021525"
  role: "Developer"
time_spent: "Chưa ghi nhận"
phase: "Phase 2 - Architecture Analysis & Refactoring"
category: "perf"
status: "completed"
task_ref:
  issue: "Chưa xác định"
  branch: "main"
  commits: []
tags:
  - "performance"
  - "caffeine"
  - "product-cache"
  - "phase-2"
---

# Cache Caffeine cho truy vấn sản phẩm

## 1. Tóm tắt nhanh

Đã thêm cache trong bộ nhớ cho truy vấn danh sách và chi tiết sản phẩm trên một API instance. Cache được xóa khi các thay đổi sản phẩm hoặc tồn kho commit thành công; bộ kiểm thử Maven đã chạy thành công. Chưa đo lại hiệu năng sau thay đổi.

## 2. Mục tiêu & Phạm vi

- Giảm số lần truy vấn lặp lại đến DB khi đọc sản phẩm, bằng cache-aside.
- Phân biệt danh sách theo từ khóa, trang và kích thước; phân biệt chi tiết theo ID.
- Giới hạn thời gian và số lượng mục trong cache; giữ việc kiểm tra tồn kho trong transaction với DB.
- Phạm vi triển khai là một API instance; cache phân tán và đo lại stress test là công việc tiếp theo.

## 3. Bối cảnh & Vấn đề kiến trúc

Theo [báo cáo stress test](../performance/REPORT.md), workload giả lập dành 60% thao tác cho danh sách và 15% cho chi tiết sản phẩm. Ở mức 500 user, báo cáo ghi p95 lần lượt 550 ms và 530 ms. Đây là cơ sở chọn cache để thử cải thiện; số liệu hiện có chưa chứng minh DB là nguyên nhân chính hoặc định lượng được hiệu quả cache.

Trước thay đổi, adapter truy vấn DB cho các lượt đọc. Kết quả stress test được lưu ở `performance/results/20261116T145327Z/`; metadata ghi commit ứng dụng `60864d7`.

## 4. Chi tiết công việc đã thực hiện

- Thêm dependency Caffeine vào `pom.xml`.
- Tại `ProductRepositoryAdapter`, cache trang theo từ khóa đã trim, `page`, `size` và thế hệ cache; cache chi tiết theo ID và thế hệ cache. Kết quả không tìm thấy không được lưu vào cache chi tiết.
- TTL mặc định 30 giây sau khi ghi và tối đa 1.000 mục cho mỗi vùng cache. Có thể cấu hình `app.product-cache.ttl` và `app.product-cache.maximum-size`.
- `save` và `saveAll` vô hiệu hóa cả hai vùng cache sau commit; nếu không có transaction ngoài thì vô hiệu hóa sau thao tác ghi. Thế hệ cache giúp kết quả đọc đang chạy từ trước lần vô hiệu hóa không được dùng cho lần đọc sau.
- Đọc trong transaction và `findAllByIdsForUpdate` đi thẳng đến DB. Đặt/hủy đơn và điều chỉnh kho đi qua thao tác ghi sản phẩm; checkout tiếp tục kiểm tra tồn kho trong DB.
- Thêm `ProductCacheTest` để kiểm tra phân biệt key và vô hiệu hóa sau commit. Cập nhật hướng dẫn trong `performance/README.md`.

## 5. Kết quả & Kiểm chứng

Lệnh `./mvnw.cmd test` chạy thành công trên Windows với H2: **136 test, 0 failure, 0 error, 0 skipped**. Đây là kiểm thử chức năng, không phải benchmark hiệu năng.

| Chỉ số ở 500 user | Trước: báo cáo stress test | Sau khi thêm cache |
| --- | ---: | --- |
| RPS tổng | 207,04 | Chưa đo |
| p95 tổng | 730 ms | Chưa đo |
| p95 danh sách sản phẩm | 550 ms | Chưa đo |
| p95 chi tiết sản phẩm | 530 ms | Chưa đo |

## 6. Khó khăn & Giới hạn

- Maven Wrapper ban đầu bị sandbox chặn ghi vào thư mục `.m2`; chạy ngoài sandbox đã thành công.
- Cache ở bộ nhớ từng instance, không đồng bộ giữa nhiều instance hoặc với thay đổi DB từ bên ngoài ứng dụng. Các lần ghi sản phẩm xóa toàn bộ cache sản phẩm, nên workload ghi nhiều có thể làm giảm tỷ lệ hit.
- Chưa đo cache hit, CPU, tải DB hoặc hiệu năng sau thay đổi; không khẳng định mức cải thiện.

## 7. Kế hoạch tiếp theo

- Commit phiên bản cần đo và chạy lại stress test trên môi trường, workload và thời lượng tương đương baseline.
- So sánh RPS, p95/p99 và lỗi tại cùng mức tải; thu thập tỷ lệ cache hit và tải DB để đánh giá tác động.
- Xem xét cache dùng chung và cơ chế đồng bộ nếu triển khai nhiều API instance.

## 8. Tài liệu tham khảo & Tệp liên quan

- [Báo cáo hiệu năng](../performance/REPORT.md) và [hướng dẫn benchmark](../performance/README.md).
- [ProductRepositoryAdapter](../src/main/java/vn/edu/sales/infrastructure/persistence/product/ProductRepositoryAdapter.java).
- [ProductCacheTest](../src/test/java/vn/edu/sales/infrastructure/persistence/ProductCacheTest.java).
