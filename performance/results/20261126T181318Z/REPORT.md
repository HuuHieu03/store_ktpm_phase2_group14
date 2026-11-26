# Báo cáo kiểm thử tải Single Store Team

- Kịch bản: `stress`
- Thời điểm UTC: 2026-11-26T18:14:58.456637+00:00
- Git commit: `88c1262f15012af1adda8b7b2cd4ac29f47527eb`
- Nơi chạy: Kaggle CPU Notebook
- CPU: 4 luồng; RAM: 31.35 GiB
- Java: openjdk version "21.0.13.1" 2026-11-17
- MySQL: 8.0.46-0ubuntu0.24.04.5
- Locust: locust 2.32.1 from /usr/local/lib/python3.13/dist-packages/locust (Python 3.13.18)
- API và MySQL chạy trên cùng phiên CPU; Locust gọi qua localhost.
- Trọng số tác vụ mục tiêu: 60% danh sách sản phẩm, 15% chi tiết, 10% đăng nhập, 10% đơn của tôi, 5% tạo đơn.
- Mỗi người dùng ảo có một sản phẩm tồn kho lớn riêng; request chuẩn bị tài khoản/sản phẩm không tính vào số liệu Locust.

| Pha | Users | Giây | Requests | Lỗi | Lỗi % | RPS | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| smoke | 5 | 60 | 147 | 0 | 0.0 | 2.49 | 5.0 | 100.0 | 140.0 |
| baseline | 10 | 60 | 300 | 0 | 0.0 | 5.08 | 4.0 | 100.0 | 110.0 |
| main | 30 | 90 | 1314 | 0 | 0.0 | 14.74 | 3.0 | 98.0 | 100.0 |
| high | 60 | 90 | 2562 | 0 | 0.0 | 28.73 | 3.0 | 98.0 | 110.0 |
| stress_100 | 100 | 90 | 4245 | 0 | 0.0 | 47.6 | 3.0 | 100.0 | 120.0 |
| stress_150 | 150 | 90 | 6396 | 0 | 0.0 | 71.7 | 2.0 | 99.0 | 120.0 |
| stress_200 | 200 | 90 | 8474 | 0 | 0.0 | 94.94 | 2.0 | 100.0 | 130.0 |
| stress_250 | 250 | 90 | 10617 | 0 | 0.0 | 118.9 | 3.0 | 110.0 | 150.0 |
| stress_300 | 300 | 90 | 12769 | 0 | 0.0 | 142.94 | 3.0 | 120.0 | 180.0 |
| stress_400 | 400 | 90 | 17000 | 0 | 0.0 | 189.8 | 4.0 | 190.0 | 410.0 |
| stress_500 | 500 | 90 | 20336 | 0 | 0.0 | 226.26 | 6.0 | 1100.0 | 1400.0 |

## Tranh chấp khoá InnoDB (chênh lệch trong pha)

| Pha | Row lock waits | Row lock time ms | Lock timeouts | Deadlocks |
|---|---:|---:|---:|---:|
| smoke | 0 | 0 | 0 | 0 |
| baseline | 0 | 0 | 0 | 0 |
| main | 0 | 0 | 0 | 0 |
| high | 0 | 0 | 0 | 0 |
| stress_100 | 0 | 0 | 0 | 0 |
| stress_150 | 0 | 0 | 0 | 0 |
| stress_200 | 0 | 0 | 0 | 0 |
| stress_250 | 0 | 0 | 0 | 0 |
| stress_300 | 0 | 0 | 0 | 0 |
| stress_400 | 0 | 0 | 0 | 0 |
| stress_500 | 0 | 0 | 0 | 0 |

## Đối chiếu tự động

- Tổng request trong các pha có CSV: 84160; tổng request thất bại: 0.
- RPS từ pha đầu đến pha cuối có số liệu: 2.49 → 226.26; p95 trong khoảng 98.0–1100.0 ms.
- Số liệu chỉ mô tả các pha có trong bảng; một pha smoke không chứng minh toàn bộ bốn mức tải.
- Các pha chạy tuần tự trên cùng database, dữ liệu người dùng/đơn tăng dần; đây là giới hạn của phép so sánh giữa các pha.
- Kết quả chỉ mô tả commit và môi trường ở trên, không phải cam kết hiệu năng production.

## Tệp minh chứng

`metadata.json`, `summary.csv`, `*_stats.csv`, `*_failures.csv`, `*_stats_history.csv`, `*_report.html`, `*_resources.csv`, `*_locust.log`, `api.log`.

- Quét api.log: 0 dòng có mẫu ERROR/Exception. Đây là bộ lọc văn bản, không thay thế đọc log hoặc chứng minh tuyệt đối không có lỗi.
- Đối chiếu thêm *_failures.csv, *_exceptions.csv, HTML và commit trước khi nghiệm thu.
