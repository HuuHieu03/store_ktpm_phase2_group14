# 📋 Quy chuẩn Ghi nhận Nhật ký Công việc (Worklogs)
> **Dự án:** Kiến trúc Phần mềm (KTPM) - Phase 2: Cải tiến & Tái cấu trúc Kiến trúc Hệ thống  
> **Nhóm thực hiện:** Group 14  
> **Repository:** `store_ktpm_phase2_group14`  
> **Thư mục:** `worklogs/` (Được theo dõi và commit lên Git)

---

## 🎯 1. Mục đích và Ý nghĩa

Thư mục `worklogs/` là nơi toàn bộ thành viên trong nhóm ghi lại minh chứng tiến độ, các phân tích kiến trúc, quyết định kỹ thuật, và kết quả thực nghiệm trong suốt Phase 2.

Thư mục này phục vụ 2 nhóm độc giả:
1. **Human-Friendly (Con người):**
   - Giảng viên và người chấm điểm theo dõi được đóng góp thực tế và năng lực của từng thành viên.
   - Các thành viên khác trong nhóm nắm bắt được tiến độ chéo, hiểu lý do đưa ra các quyết định kiến trúc (Architectural Decision Records - ADR) mà không cần hỏi lại.
2. **AI-Friendly (Công cụ & Trợ lý AI):**
   - Sử dụng định dạng **YAML Frontmatter** có cấu trúc chuẩn mực ở đầu mỗi file để AI Agents (như Antigravity, Claude, ChatGPT, Cursor) hoặc các script tự động có thể parse, index, tổng hợp báo cáo tiến độ và tìm kiếm thông tin nhanh chóng.
   - Các phân đoạn nội dung có tiêu đề Markdown nhất quán giúp AI trích xuất thông tin chính xác khi người dùng cần tạo báo cáo tổng kết giai đoạn.

---

## 📏 2. Quy ước đặt tên file (Strict Naming Convention)

Mọi thành viên **bắt buộc tuân thủ nghiêm ngặt** quy tắc đặt tên file worklog như sau:

```text
YYYY-MM-DD_<author-alias>_<category>_<short-slug>.md
```

### Chi tiết các trường:

| Thành phần | Định dạng | Quy tắc & Ràng buộc | Ví dụ hợp lệ |
| :--- | :--- | :--- | :--- |
| `YYYY-MM-DD` | Ngày tháng năm (ISO 8601) | Đủ 4 chữ số năm, 2 chữ số tháng, 2 chữ số ngày. | `2026-11-14` |
| `<author-alias>` | Tên viết tắt hoặc GitHub username | Chữ thường, không dấu, không khoảng trắng, thống nhất trong cả dự án. | `duongnv`, `hailt`, `namnt` |
| `<category>` | Danh mục loại công việc | Chọn chính xác 1 trong các từ khóa chuẩn ở bảng danh mục dưới. | `arch`, `refactor`, `perf` |
| `<short-slug>` | Mô tả ngắn gọn nội dung | Từ 2 đến 5 từ, cách nhau bằng dấu gạch ngang `-`, không dấu tiếng Việt. | `analyze-bottlenecks`, `redis-caching` |

### Danh mục chuẩn (`<category>`):

- `arch`: Phân tích kiến trúc hiện tại, thiết kế kiến trúc mục tiêu, đánh giá mẫu thiết kế (Design patterns, Architectural patterns, C4 model).
- `refactor`: Tái cấu trúc mã nguồn, tách module, phân tầng (Layered/Hexagonal), áp dụng SOLID/Clean Code.
- `perf`: Tối ưu hóa hiệu năng, giải quyết điểm nghẽn (bottleneck), cache, query tuning, load testing.
- `db`: Thiết kế/tối ưu hóa cơ sở dữ liệu, indexing, migration, transaction isolation, xử lý concurrency.
- `test`: Viết unit test, integration test, performance benchmark (JMeter/Gatling).
- `infra`: Cấu hình Docker, CI/CD, môi trường chạy thử nghiệm, monitoring/observability.
- `docs`: Viết tài liệu báo cáo, vẽ sơ đồ kiến trúc, đặc tả API.
- `meeting`: Ghi biên bản cuộc họp kỹ thuật, chốt phương án kiến trúc trong nhóm.

### Ví dụ tên file hợp lệ:
- `2026-11-14_duongnv_arch_analyze-order-bottlenecks.md`
- `2026-11-15_hailt_perf_implement-redis-cache.md`
- `2026-11-16_namnt_refactor_extract-inventory-module.md`
- `2026-11-17_duongnv_meeting_architecture-review-phase2.md`

> ❌ **Các tên file KHÔNG hợp lệ (sẽ bị yêu cầu đổi lại):**
> - `worklog.md` *(Không có tác giả, ngày tháng, nội dung)*
> - `2026-11-14_duong_fix bug.md` *(Chứa khoảng trắng, category không chuẩn)*
> - `14-11-2026_hailt_perf.md` *(Sai định dạng ngày, thiếu slug)*
> - `2026-11-15_BaoCaoTienDo.md` *(Không tuân thủ format quy ước)*

---

## 📑 3. Cấu trúc nội dung chuẩn của một Worklog

Mỗi file worklog phải được khởi tạo từ file mẫu [`TEMPLATE.md`](TEMPLATE.md) và gồm 2 phần chính:

### Phần 1: Metadata Header (YAML Frontmatter)
Bắt buộc đặt ở dòng đầu tiên của file, bao bọc bởi hai đường viền `---`:

```yaml
---
worklog_id: "WL-20261114-DUONGNV-001"
title: "Phân tích điểm nghẽn hiệu năng và luồng xử lý đơn hàng"
date: "2026-11-14"
author:
  name: "Nguyễn Văn Dương"
  username: "duongnv"
  student_id: "20210001"
  role: "Backend Architect"
time_spent: "3.5h"
phase: "Phase 2 - Architecture Analysis & Refactoring"
category: "arch"
status: "completed" # completed | in-progress | blocked
task_ref:
  issue: "#14"
  branch: "feature/order-perf-analysis"
  commits:
    - "a1b2c3d"
    - "e5f6g7h"
tags:
  - "architecture"
  - "performance"
  - "order-service"
  - "database-lock"
---
```

### Phần 2: Thân bài (Markdown Body)
Phải có đầy đủ các mục lớn:
1. **Tóm tắt nhanh (Executive Summary):** 2-3 câu ngắn gọn về việc đã làm và kết quả đạt được.
2. **Mục tiêu & Phạm vi (Objectives & Scope):** Làm rõ phiên làm việc giải quyết điều gì.
3. **Bối cảnh & Vấn đề kiến trúc (Architectural Context):** Hiện trạng hệ thống (AS-IS) gặp vấn đề gì?
4. **Chi tiết công việc đã thực hiện (Detailed Work Done):**
   - Phân tích mã nguồn / CSDL.
   - Quyết định kiến trúc & Đề xuất (TO-BE).
   - Code snippet, config hoặc sơ đồ Mermaid minh họa.
5. **Kết quả & Kiểm chứng (Results & Verification):** Số liệu đo đạc, benchmark hoặc kết quả test so sánh Trước/Sau.
6. **Khó khăn, Thách thức & Rào cản (Blockers & Challenges):** Vấn đề phát sinh, rủi ro kiến trúc.
7. **Kế hoạch tiếp theo (Next Steps):** Hành động kế tiếp cần làm hoặc bàn giao cho ai.
8. **Tài liệu tham khảo (References):** Link tài liệu, issue, PR, đặc tả.

---

## 🚀 4. Quy trình làm việc (Workflow) dành cho thành viên

Khi bắt đầu phiên làm việc:
1. **Khởi tạo:**
   - Vào thư mục `worklogs/`.
   - Copy file `TEMPLATE.md` thành file mới theo quy tắc:
     `YYYY-MM-DD_<author-alias>_<category>_<short-slug>.md`
2. **Cập nhật nội dung:**
   - Cập nhật đầy đủ thông tin vào phần YAML header.
   - Ghi lại các phát hiện, sơ đồ, quyết định và kết quả đo đạc trong quá trình làm.
3. **Commit & Push lên Git:**
   - Đảm bảo file worklog đi kèm hoặc commit riêng với message chuẩn:
     ```bash
     git add worklogs/YYYY-MM-DD_<author-alias>_<category>_<short-slug>.md
     git commit -m "docs(worklog): add worklog YYYY-MM-DD <author> <category> <slug>"
     git push origin <your-branch>
     ```
4. **Quy tắc văn hóa nhóm:**
   - **Bất biến (Append-only):** Không sửa nội dung worklog của thành viên khác. Nếu cần bổ sung hoặc phản biện, tạo worklog mới hoặc để lại comment trên PR.
   - **Trung thực & Thực tế:** Ghi nhận cả các lỗi sai, thất bại của thử nghiệm và cách khắc phục; đây là dữ liệu quý giá cho báo cáo kiến trúc.

---

## 🤖 5. Hướng dẫn sử dụng cùng AI Assistant

Các công cụ AI có thể hỗ trợ bạn viết và tổng hợp worklog hiệu quả:

### Gợi ý Prompt tạo worklog tự động:
> *"Dựa trên các thay đổi trong commit `abc1234` và phân tích file `src/main/java/.../OrderService.java`, hãy giúp tôi điền nội dung vào mẫu `worklogs/TEMPLATE.md` với category `perf`, tác giả `duongnv`."*

### Gợi ý Prompt tổng hợp tiến độ nhóm:
> *"Hãy đọc tất cả các file trong thư mục `worklogs/`, trích xuất các metadata header và tổng hợp thành bảng tiến độ tuần này của Nhóm 14, phân loại theo tác giả và category."*
