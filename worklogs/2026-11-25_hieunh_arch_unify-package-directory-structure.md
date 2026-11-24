---
worklog_id: "WL-20261125-HIEUNH-001"
title: "ARCH-01: Đồng bộ khai báo package khớp với cấu trúc thư mục"
date: "2026-11-25"
author:
  name: "Nguyễn Hữu Hiệu"
  username: "HuuHieu03"
  student_id: "21020000"
  role: "Backend Architect"
time_spent: "0.5h"
phase: "Phase 2 - Architecture Analysis & Refactoring"
category: "arch"
status: "completed"
task_ref:
  issue: "ARCH-01"
  branch: "fix/arch-01-package-structure"
  commits: []
tags:
  - "arch-01"
  - "package-structure"
  - "hexagonal-architecture"
  - "archunit"
---

# ARCH-01: Thống nhất khai báo package khớp với cấu trúc thư mục

## 1. Tóm tắt

- **Vấn đề:** Bốn lớp trong `src/main/java` khai báo tên package không khớp với vị trí thư mục vật lý trên đĩa (`application.service` thay vì `application.services`; `api.product` thay vì `api.system.product`), gây sai lệch quy ước đặt tên và khó điều hướng mã nguồn.
- **Kết quả:** Đã đồng bộ khai báo `package` của cả 4 lớp về đúng thư mục vật lý và cập nhật toàn bộ câu lệnh `import` liên quan trong `main` và `test`.
- **Kiểm chứng:** `./mvnw clean verify` chạy thành công toàn bộ 142 unit/ArchUnit test và 2 integration test (`BUILD SUCCESS`, 0 lỗi).

---

## 2. Chi tiết các tệp không khớp đã phát hiện và sửa đổi

Quét toàn bộ tệp `.java` trong `src/main/java` và `src/test/java`, ghi nhận 4 tệp sai lệch duy nhất:

| Tệp mã nguồn | Package cũ | Package mới (khớp thư mục) |
| :--- | :--- | :--- |
| `src/main/java/vn/edu/sales/application/services/ProductService.java` | `vn.edu.sales.application.service` | `vn.edu.sales.application.services` |
| `src/main/java/vn/edu/sales/application/services/InventoryService.java` | `vn.edu.sales.application.service` | `vn.edu.sales.application.services` |
| `src/main/java/vn/edu/sales/api/system/product/ProductController.java` | `vn.edu.sales.api.product` | `vn.edu.sales.api.system.product` |
| `src/main/java/vn/edu/sales/api/system/product/InventoryController.java` | `vn.edu.sales.api.product` | `vn.edu.sales.api.system.product` |

### Các tệp cập nhật `import`:
- `src/main/java/vn/edu/sales/config/ApplicationBeanConfig.java`: cập nhật import `ProductService` và `InventoryService` sang `vn.edu.sales.application.services`.
- `src/main/java/vn/edu/sales/api/system/product/ProductController.java`: cập nhật import `ProductService`.
- `src/main/java/vn/edu/sales/api/system/product/InventoryController.java`: cập nhật import `InventoryService`.
- `src/test/java/vn/edu/sales/infrastructure/persistence/ProductAuditAndSeedIT.java`: cập nhật import `ProductService`.
- `src/test/java/vn/edu/sales/application/service/ProductServiceTest.java`: bổ sung `import vn.edu.sales.application.services.ProductService;` (trước đây dùng chung package nên không cần import).

---

## 3. Kết quả kiểm chứng (Verification)

1. **Rà soát tự động bằng script:** Chạy kiểm tra đối chiếu đường dẫn thư mục với dòng `package` trên toàn bộ tệp `.java` trong `src/` — không còn tệp nào lệch.
2. **Tìm kiếm tham chiếu cũ:** Quét toàn bộ `src/` không còn chuỗi `vn.edu.sales.application.service.` hoặc `vn.edu.sales.api.product.`.
3. **Maven Verify & ArchUnit:**
```text
./mvnw clean verify
- Surefire: Tests run: 142, Failures: 0, Errors: 0, Skipped: 0 (bao gồm ArchitectureTest: 4/4 pass)
- Failsafe: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 (ProductAuditAndSeedIT: 2/2 pass)
- BUILD SUCCESS (Total time: 01:25 min)
```
