# Báo cáo kiểm thử tải Single Store Team

- Kịch bản: `hotspot`
- Thời điểm UTC: 2026-11-21T15:40:03.393324+00:00
- Git commit: `462fecfd1ed6225d557e7181a069cf41c837c439`
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
| hotspot_10 | 10 | 60 | 2590 | 0 | 0.0 | 43.83 | 220.0 | 290.0 | 350.0 |
| hotspot_50 | 50 | 60 | 2824 | 0 | 0.0 | 47.76 | 1000.0 | 1100.0 | 1200.0 |
| hotspot_100 | 100 | 90 | 4332 | 0 | 0.0 | 48.59 | 2000.0 | 2300.0 | 2300.0 |
| hotspot_200 | 200 | 90 | 4280 | 0 | 0.0 | 47.99 | 4100.0 | 4300.0 | 4400.0 |
| oversell_100 | 100 | 60 | 31408 | 31108 | 99.04 | 523.68 | 110.0 | 190.0 | 330.0 |

## Toàn vẹn tồn kho SKU nóng

Đúng khi `tồn đầu − đã bán = tồn cuối`, tổng `inventory_movements` = tồn cuối, tồn cuối ≥ 0 và không bán vượt tồn đầu. Runner chờ các đơn còn xếp hàng commit xong rồi đọc cả bốn số trong một câu SQL (cùng một snapshot). Số 201 của Locust chỉ để đối chiếu: request cuối có thể hoàn tất sau khi Locust dừng.

| Pha | Tồn đầu | Đã bán | Đơn DB | 201 Locust | Tồn cuối | Tổng movements | Kết quả |
|---|---:|---:|---:|---:|---:|---:|---|
| hotspot_10 | 1000000 | 2629 | 2629 | 2590 | 997371 | 997371 | OK |
| hotspot_50 | 1000000 | 2902 | 2902 | 2824 | 997098 | 997098 | OK |
| hotspot_100 | 1000000 | 4457 | 4457 | 4332 | 995543 | 995543 | OK |
| hotspot_200 | 1000000 | 4504 | 4504 | 4280 | 995496 | 995496 | OK |
| oversell_100 | 300 | 300 | 300 | 300 | 0 | 0 | OK |

## Tranh chấp khoá InnoDB (chênh lệch trong pha)

| Pha | Row lock waits | Row lock time ms | Lock timeouts | Deadlocks |
|---|---:|---:|---:|---:|
| hotspot_10 | 2628 | 510465 | 0 | 0 |
| hotspot_50 | 2901 | 531415 | 0 | 0 |
| hotspot_100 | 4456 | 813006 | 0 | 0 |
| hotspot_200 | 4503 | 831648 | 0 | 0 |
| oversell_100 | 30388 | 248033 | 0 | 0 |

## Đối chiếu tự động

- Tổng request trong các pha có CSV: 45434; tổng request thất bại: 31108.
- RPS từ pha đầu đến pha cuối có số liệu: 43.83 → 523.68; p95 trong khoảng 190.0–4300.0 ms.
- Số liệu chỉ mô tả các pha có trong bảng; một pha smoke không chứng minh toàn bộ bốn mức tải.
- Các pha chạy tuần tự trên cùng database, dữ liệu người dùng/đơn tăng dần; đây là giới hạn của phép so sánh giữa các pha.
- Kết quả chỉ mô tả commit và môi trường ở trên, không phải cam kết hiệu năng production.

## Tệp minh chứng

`metadata.json`, `summary.csv`, `*_stats.csv`, `*_failures.csv`, `*_stats_history.csv`, `*_report.html`, `*_resources.csv`, `*_locust.log`, `api.log`.

- Quét api.log: 0 dòng có mẫu ERROR/Exception. Đây là bộ lọc văn bản, không thay thế đọc log hoặc chứng minh tuyệt đối không có lỗi.
- Đối chiếu thêm *_failures.csv, *_exceptions.csv, HTML và commit trước khi nghiệm thu.
