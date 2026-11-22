# Báo cáo kiểm thử tải Single Store Team

- Kịch bản: `hotspot`
- Thời điểm UTC: 2026-11-22T09:06:59.734334+00:00
- Git commit: `58af98f832a990599e2d4c49e319906f23551480`
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
| hotspot_10 | 10 | 60 | 7039 | 5789 | 82.24 | 118.58 | 71.0 | 160.0 | 220.0 |
| hotspot_50 | 50 | 60 | 11965 | 10492 | 87.69 | 201.41 | 220.0 | 340.0 | 430.0 |
| hotspot_100 | 100 | 90 | 19820 | 17479 | 88.19 | 221.15 | 430.0 | 560.0 | 830.0 |
| hotspot_200 | 200 | 90 | 18597 | 16415 | 88.27 | 207.56 | 930.0 | 1300.0 | 1800.0 |
| oversell_100 | 100 | 60 | 29382 | 29082 | 98.98 | 494.9 | 110.0 | 310.0 | 480.0 |

## Toàn vẹn tồn kho SKU nóng

Đúng khi `tồn đầu − đã bán = tồn cuối`, tổng `inventory_movements` = tồn cuối, tồn cuối ≥ 0 và không bán vượt tồn đầu. Runner chờ các đơn còn xếp hàng commit xong rồi đọc cả bốn số trong một câu SQL (cùng một snapshot). Số 201 của Locust chỉ để đối chiếu: request cuối có thể hoàn tất sau khi Locust dừng.

| Pha | Tồn đầu | Đã bán | Đơn DB | 201 Locust | Tồn cuối | Tổng movements | Kết quả |
|---|---:|---:|---:|---:|---:|---:|---|
| hotspot_10 | 1000000 | 1259 | 1259 | 1250 | 998741 | 998741 | OK |
| hotspot_50 | 1000000 | 1490 | 1490 | 1473 | 998510 | 998510 | OK |
| hotspot_100 | 1000000 | 2355 | 2355 | 2341 | 997645 | 997645 | OK |
| hotspot_200 | 1000000 | 2209 | 2209 | 2182 | 997791 | 997791 | OK |
| oversell_100 | 300 | 300 | 300 | 300 | 0 | 0 | OK |

## Tranh chấp khoá InnoDB (chênh lệch trong pha)

| Pha | Row lock waits | Row lock time ms | Lock timeouts | Deadlocks |
|---|---:|---:|---:|---:|
| hotspot_10 | 13385 | 174987 | 0 | 5827 |
| hotspot_50 | 22065 | 203900 | 0 | 10581 |
| hotspot_100 | 36080 | 298328 | 0 | 17583 |
| hotspot_200 | 34209 | 294610 | 0 | 16608 |
| oversell_100 | 4732 | 43598 | 0 | 2256 |

## Đối chiếu tự động

- Tổng request trong các pha có CSV: 86803; tổng request thất bại: 79257.
- RPS từ pha đầu đến pha cuối có số liệu: 118.58 → 494.9; p95 trong khoảng 160.0–1300.0 ms.
- Số liệu chỉ mô tả các pha có trong bảng; một pha smoke không chứng minh toàn bộ bốn mức tải.
- Các pha chạy tuần tự trên cùng database, dữ liệu người dùng/đơn tăng dần; đây là giới hạn của phép so sánh giữa các pha.
- Kết quả chỉ mô tả commit và môi trường ở trên, không phải cam kết hiệu năng production.

## Tệp minh chứng

`metadata.json`, `summary.csv`, `*_stats.csv`, `*_failures.csv`, `*_stats_history.csv`, `*_report.html`, `*_resources.csv`, `*_locust.log`, `api.log`.

- Quét api.log: 52855 dòng có mẫu ERROR/Exception. Đây là bộ lọc văn bản, không thay thế đọc log hoặc chứng minh tuyệt đối không có lỗi.
- Đối chiếu thêm *_failures.csv, *_exceptions.csv, HTML và commit trước khi nghiệm thu.
