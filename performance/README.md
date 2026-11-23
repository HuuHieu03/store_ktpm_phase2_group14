# Kaggle CPU — Benchmark & Đo tải Repository Nhóm

Cache sản phẩm dùng Caffeine trong bộ nhớ của một API instance. Cache danh sách
theo từ khóa, trang và kích thước; cache chi tiết theo ID. Mặc định mỗi vùng cache
có tối đa 1.000 mục và TTL 30 giây sau khi ghi; cấu hình bằng
`app.product-cache.maximum-size` và `app.product-cache.ttl` (ví dụ `30s`).
Khi sản phẩm hoặc tồn kho được ghi thành công, cache bị xóa sau commit; đọc trong
transaction và đọc có khóa đi thẳng tới DB. Mỗi instance có cache riêng, do đó
cấu hình này dành cho một instance và không đồng bộ giữa nhiều instance.

Đã có [Báo cáo tổng kết hiệu năng](REPORT.md) bao gồm:
1. **Pha 1 Baseline:** [Minh chứng Kaggle 21/10/2026](results/20261021T023505Z) cho commit `941ec24` (4 pha: 5, 10, 30, 60 users).
2. **Pha 2 Stress Ladder & Capacity Limit:** [Minh chứng Kaggle 16/11/2026](results/20261116T145327Z) cho commit `60864d7` (11 pha: 5 đến 500 users, tổng 82.048 requests, bão hòa 207.04 RPS, xác định điểm gãy độ trễ tại 300–400 users).
3. **Pha 2 Hotspot (TEST-00b):** [Minh chứng Kaggle 21/11/2026](results/20261121T153809Z) cho commit `462fecf` (10–200 user cùng đặt 1 SKU: trần ~48 đơn/giây, p95 290 ms → 4,3 s; toàn vẹn tồn kho OK cả 5 pha, oversell bán đúng 300/300). `api.log` đã che mật khẩu dev do Spring tự sinh.
4. **CONC-01.3, bị bác:** [Minh chứng Kaggle 22/11/2026](results/20261122T090451Z) cho commit `58af98f` (ghi đơn trước, trừ kho sau): deadlock InnoDB do khoá S của khoá ngoại `order_items → products`, 82–88% đơn trả 409, chỉ ~21–26 đơn/giây; toàn vẹn tồn kho vẫn OK. Đã revert ở `a75256d`; phân tích ở [ADR CONC-01](../worklogs/2026-11-22_sonha_db_adr-atomic-stock-update.md). `api.log` đã che mật khẩu dev và cắt 105.700 dòng deadlock lặp lại (có ghi chú trong file).
5. **CONC-01.5, sau cải tiến:** [Minh chứng Kaggle 22/11/2026](results/20261122T104048Z) cho commit `a75256d` (UPDATE nguyên tử, UPDATE trước khi ghi đơn): 0 deadlock, 0 lỗi, toàn vẹn OK, oversell 300/300, nhưng vẫn ~43–49 đơn/giây, **không cải thiện** so với `FOR UPDATE`. So sánh ở [REPORT.md, PHẦN 4](REPORT.md). `api.log` đã che mật khẩu dev.

Bộ runner/workload/report được thiết kế chạy trực tiếp trên Kaggle CPU Notebook: database `single_store_team`, sáu bảng, Flyway V1/V2, JAR `single-store`, API sản phẩm/đơn và admin bootstrap.

## Thực hiện

1. Chạy Maven verify và CI trước. Commit/push hoặc merge phiên bản cần đo, kiểm
   tra commit đó có performance/kaggle_run.py. Không benchmark code chưa commit.
2. Tạo Kaggle Python Notebook, Accelerator=None (CPU), bật Internet. Import
   `kaggle_load_test.ipynb`. Ở cell code đầu, đặt `REF` = SHA đã push và
   `SCENARIO` = `stress` | `hotspot` | `soak`.
3. Chạy clone/checkout, rồi runner. Để chạy đủ các pha, không đặt LOAD_SMOKE_ONLY.
   Nếu chỉ thử khả thi, đặt LOAD_SMOKE_ONLY=1 (chỉ chạy pha đầu); sau đó dùng session
   Kaggle mới cho lượt đo đầy đủ. Không chạy lại runner trên schema đã có dữ liệu.
4. Cài MySQL 8, Java 21 và Locust có thể mất vài phút. Nếu apt/pip/Internet bị
   chặn, giữ log lỗi; không đổi sang H2 rồi tuyên bố đã đo MySQL.
5. Tải ZIP ở `/kaggle/working/single_store_load_results.zip` từ Output.
   Review metadata, REPORT.md, summary.csv, *_stats.csv, *_failures.csv,
   *_exceptions.csv, *_stats_history.csv, *_report.html và api.log.

Runner chỉ cho chạy Linux/root trong /kaggle/working; MySQL/API nằm trong chính
session đó, không kết nối MySQL trên máy cá nhân. Không cần Docker trong Kaggle.
MySQL dùng schema mới; Flyway tạo V1/V2 và Hibernate validate. Credential ngẫu
nhiên chỉ dùng trong phiên; không xuất fixture có JWT/password vào minh chứng.
Runner build JAR với test skip để không trộn thời gian JUnit vào phép đo;
đây KHÔNG phải bằng chứng các test Java đã pass.

## Kịch bản

| Pha | Người dùng ảo | Thời gian |
|---|---:|---:|
| smoke | 5 | 60 giây |
| baseline | 10 | 120 giây |
| main | 30 | 300 giây |
| high | 60 | 300 giây, không tiếp tục khi main lỗi |

Mỗi user đợi ngẫu nhiên 1–3 giây giữa tác vụ, tăng tải 5 user/giây.
Trọng số: 60% danh sách sản phẩm, 15% detail, 10% login, 10% đơn của tôi, 5% tạo đơn.
Chuẩn bị tài khoản/sản phẩm riêng trước từng pha, không tính request chuẩn bị.
Không dùng tải ghi liên tục không nghỉ để suy ra kết quả tương đương kịch bản này.

## Kịch bản hotspot (TEST-00b)

Chọn bằng `SCENARIO = 'hotspot'` trong notebook (biến môi trường `LOAD_SCENARIO`).
Mọi user dùng `locustfile_hotspot.py`: cùng đặt **một SKU** (quantity 1), không think time,
để đo tranh chấp khoá dòng sản phẩm khi đặt hàng (`SELECT … FOR UPDATE` trước CONC-01, `UPDATE` nguyên tử sau CONC-01). Mỗi pha admin tạo một SKU nóng mới.

| Pha | Users | Thời gian | Tồn kho SKU nóng | Mục đích |
|---|---:|---:|---:|---|
| hotspot_10 | 10 | 60 giây | 1.000.000 | Tranh chấp thấp |
| hotspot_50 | 50 | 60 giây | 1.000.000 | |
| hotspot_100 | 100 | 90 giây | 1.000.000 | |
| hotspot_200 | 200 | 90 giây | 1.000.000 | Tranh chấp cao |
| oversell_100 | 100 | 60 giây | 300 | Kiểm tra không bán vượt tồn |

Sau mỗi pha runner đọc MySQL và ghi vào `metadata.json` + `REPORT.md`:

- **Toàn vẹn tồn kho**: `tồn đầu − đã bán = tồn cuối`, tổng `inventory_movements` = tồn cuối,
  tồn cuối ≥ 0. Phải là `OK` ở mọi pha; `FAIL` là lỗi nghiệp vụ, không phải lỗi hiệu năng.
- **Tranh chấp khoá InnoDB**: chênh lệch `Innodb_row_lock_waits`, `Innodb_row_lock_time`,
  `lock_timeouts`, `lock_deadlocks` trong pha.
- `*_failures.csv` gom lỗi theo lý do: `out_of_stock` (kỳ vọng ở pha oversell),
  `lock_conflict` (409 CONCURRENT_CHANGE), `server_error …`; timeout giữ thông báo của Locust.
  Có request thất bại thì Locust trả exit code 1.

Mọi kịch bản (stress/hotspot/soak) còn ghi `{pha}_resources.csv` mỗi 2 giây: CPU% và RSS của
API (java), mysqld, Locust và CPU toàn máy (CPU% một process có thể > 100% trên nhiều lõi).
Chưa có số liệu connection pool Hikari; cần actuator/metrics (OBS-01).

`SCENARIO = 'soak'` chạy workload 60/15/10/10/5 với 300 user trong 30 phút (`soak_300`).

**Commit minh chứng:** `.gitignore` có `*.log`, nên `api.log` và `*_locust.log` phải thêm bằng
`git add -f performance/results/<run>/*.log`; nếu không, thư mục results sẽ thiếu log.

## Giới hạn và tiêu chí nghiệm thu

- API, MySQL và Locust chia sẻ CPU/RAM Kaggle, gọi localhost, không đo mạng WAN.
- Kịch bản stress: mỗi user có sản phẩm riêng, không đo nhiều người tranh cùng SKU;
  dùng kịch bản hotspot cho mục đích đó. Hai kịch bản không thay thế nhau.
- Dữ liệu tăng giữa các pha; so sánh RPS/p95/p99 chịu ảnh hưởng warm-up và DB lớn dần.
- Chưa có SLO chính thức; không suy ra đáp ứng production chỉ từ 0 failed request.
- Báo cáo sinh kết luận số học từ CSV và quét mẫu log. Đọc log/HTML thực tế trước
  nghiệm thu; 0 dòng ERROR theo regex không chứng minh tuyệt đối không có lỗi.
- Muốn tuyên bố chạy đủ bốn pha, phải có đủ bốn dòng và log/CSV tương ứng.

## Kiểm tra offline

```powershell
python -m unittest performance.test_report
```

Đây chỉ là test converter với fixture, không phát sinh số liệu hiệu năng thật.
Locust CSV: https://docs.locust.io/en/2.32.1/retrieving-stats.html.
Kaggle notebook: https://www.kaggle.com/docs/notebooks.
