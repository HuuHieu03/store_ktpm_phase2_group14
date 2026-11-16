# Báo cáo Đo tải & Điểm gãy (Breakpoint / Capacity Limit) — Single Store Team

- Thời điểm chạy (UTC): 2026-11-16T14:55:32.039419+00:00
- Commit ứng dụng (AS-IS): `60864d7430b5d9f93e71e33d9e92244dbbbceb97`
- Môi trường: Kaggle CPU Notebook (4 vCPU, 31.35 GiB RAM)
- Java: openjdk version "21.0.13.0.3" 2026-10-24
- MySQL: 8.0.46-0ubuntu0.24.04.5
- Locust: locust 2.32.1 from /usr/local/lib/python3.13/dist-packages/locust (Python 3.13.18)
- Cấu hình kịch bản: 60% list products, 15% product detail, 10% login, 10% my orders, 5% create order.
- Tăng tải tuần tự qua các bậc (Stress Ladder) để tìm ngưỡng gãy (Breakpoint) của hệ thống.

## 1. Bảng kết quả tổng hợp qua các pha

| Pha | Users | Thời gian (s) | Requests | Lỗi (Failures) | Tỷ lệ lỗi (%) | RPS | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| smoke | 5 | 60 | 145 | 0 | 0.0% | 2.46 | 10.0 | 110.0 | 130.0 |
| baseline | 10 | 60 | 293 | 0 | 0.0% | 4.96 | 8.0 | 100.0 | 110.0 |
| main | 30 | 90 | 1303 | 0 | 0.0% | 14.63 | 6.0 | 100.0 | 110.0 |
| high | 60 | 90 | 2549 | 0 | 0.0% | 28.58 | 6.0 | 100.0 | 110.0 |
| stress_100 | 100 | 90 | 4235 | 0 | 0.0% | 47.47 | 6.0 | 100.0 | 130.0 |
| stress_150 | 150 | 90 | 6337 | 0 | 0.0% | 71.02 | 5.0 | 110.0 | 130.0 |
| stress_200 | 200 | 90 | 8444 | 0 | 0.0% | 94.51 | 6.0 | 110.0 | 160.0 |
| stress_250 | 250 | 90 | 10644 | 0 | 0.0% | 118.96 | 6.0 | 120.0 | 190.0 |
| stress_300 | 300 | 90 | 12744 | 0 | 0.0% | 142.12 | 8.0 | 140.0 | 250.0 |
| stress_400 | 400 | 90 | 16554 | 0 | 0.0% | 184.55 | 29.0 | 240.0 | 470.0 |
| stress_500 | 500 | 90 | 18800 | 0 | 0.0% | 207.04 | 180.0 | 730.0 | 1100.0 |

## 2. Phân tích Điểm gãy & Giới hạn năng lực (Capacity Analysis)

- **Vùng hoạt động an toàn (Healthy Zone):** Tối đa **500 người dùng ảo đồng thời**, đạt thông lượng sạch **207.04 RPS**, tỷ lệ lỗi 0%.
- Hệ thống chịu tải tốt tới 500 users mà chưa vượt ngưỡng 20% lỗi.

## 3. Nhật ký lỗi & Kiểm tra log

- Quét `api.log`: Ghi nhận **0 dòng log** chứa từ khóa ERROR/Exception.

## 4. Danh mục tệp minh chứng

- `metadata.json`: Thông số môi trường chi tiết
- `summary.csv`: Thống kê rút gọn các pha
- `*_stats.csv`, `*_failures.csv`, `*_stats_history.csv`: Bảng số liệu chi tiết Locust
- `*_report.html`: Dashboard trực quan Locust
- `api.log`: Log máy chủ Spring Boot trong toàn bộ bài đo