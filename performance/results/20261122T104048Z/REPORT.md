# Báo cáo kiểm thử tải Single Store Team

- Kịch bản: `hotspot`
- Thời điểm UTC: 2026-11-22T10:42:50.064892+00:00
- Git commit: `a75256d10a4243fb212a16d35686028e8ed9337e`
- Nơi chạy: Kaggle CPU Notebook
- CPU: 4 luồng; RAM: 31.35 GiB
- Java: openjdk version "21.0.13.1" 2026-11-17
- MySQL: 8.0.46-0ubuntu0.24.04.5
- Locust: locust 2.32.1 from /usr/local/lib/python3.13/dist-packages/locust (Python 3.13.18)
- API và MySQL chạy trên cùng phiên CPU; Locust gọi qua localhost.
- Kịch bản hotspot: mọi người dùng ảo cùng đặt 1 SKU (quantity 1), không think time.
- Mỗi pha dùng một SKU nóng mới; pha `oversell_*` có tồn kho nhỏ nên lỗi `out_of_stock` là kỳ vọng, không phải lỗi hệ thống.
- Locust trả exit code 1 khi có request thất bại; đọc `*_failures.csv` để phân loại.

| Pha | Users | Giây | Requests | Lỗi | Lỗi % | RPS | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hotspot_10 | 10 | 60 | 2521 | 0 | 0.0 | 42.68 | 220.0 | 290.0 | 390.0 |
| hotspot_50 | 50 | 60 | 2513 | 0 | 0.0 | 42.51 | 1100.0 | 1400.0 | 1500.0 |
| hotspot_100 | 100 | 90 | 4099 | 0 | 0.0 | 45.95 | 2200.0 | 2300.0 | 2400.0 |
| hotspot_200 | 200 | 90 | 4141 | 0 | 0.0 | 46.43 | 4300.0 | 4500.0 | 4500.0 |
| oversell_100 | 100 | 60 | 30424 | 30124 | 99.01 | 507.92 | 110.0 | 160.0 | 430.0 |

## Toàn vẹn tồn kho SKU nóng

Đúng khi `tồn đầu − đã bán = tồn cuối`, tổng `inventory_movements` = tồn cuối, tồn cuối ≥ 0 và không bán vượt tồn đầu. Runner chờ các đơn còn xếp hàng commit xong rồi đọc cả bốn số trong một câu SQL (cùng một snapshot). Số 201 của Locust chỉ để đối chiếu: request cuối có thể hoàn tất sau khi Locust dừng.

| Pha | Tồn đầu | Đã bán | Đơn DB | 201 Locust | Tồn cuối | Tổng movements | Kết quả |
|---|---:|---:|---:|---:|---:|---:|---|
| hotspot_10 | 1000000 | 2562 | 2562 | 2521 | 997438 | 997438 | OK |
| hotspot_50 | 1000000 | 2588 | 2588 | 2513 | 997412 | 997412 | OK |
| hotspot_100 | 1000000 | 4223 | 4223 | 4099 | 995777 | 995777 | OK |
| hotspot_200 | 1000000 | 4361 | 4361 | 4141 | 995639 | 995639 | OK |
| oversell_100 | 300 | 300 | 300 | 300 | 0 | 0 | OK |

## Tranh chấp khoá InnoDB (chênh lệch trong pha)

| Pha | Row lock waits | Row lock time ms | Lock timeouts | Deadlocks |
|---|---:|---:|---:|---:|
| hotspot_10 | 2561 | 505917 | 0 | 0 |
| hotspot_50 | 2587 | 528959 | 0 | 0 |
| hotspot_100 | 4222 | 812497 | 0 | 0 |
| hotspot_200 | 4360 | 828958 | 0 | 0 |
| oversell_100 | 308 | 61204 | 0 | 0 |

## Đối chiếu tự động

- Tổng request trong các pha có CSV: 43698; tổng request thất bại: 30124.
- RPS từ pha đầu đến pha cuối có số liệu: 42.68 → 507.92; p95 trong khoảng 160.0–4500.0 ms.
- Số liệu chỉ mô tả các pha có trong bảng; một pha smoke không chứng minh toàn bộ bốn mức tải.
- Các pha chạy tuần tự trên cùng database, dữ liệu người dùng/đơn tăng dần; đây là giới hạn của phép so sánh giữa các pha.
- Kết quả chỉ mô tả commit và môi trường ở trên, không phải cam kết hiệu năng production.

## Tệp minh chứng

`metadata.json`, `summary.csv`, `*_stats.csv`, `*_failures.csv`, `*_stats_history.csv`, `*_report.html`, `*_resources.csv`, `*_locust.log`, `api.log`.

- Quét api.log: 0 dòng có mẫu ERROR/Exception. Đây là bộ lọc văn bản, không thay thế đọc log hoặc chứng minh tuyệt đối không có lỗi.
- Đối chiếu thêm *_failures.csv, *_exceptions.csv, HTML và commit trước khi nghiệm thu.
