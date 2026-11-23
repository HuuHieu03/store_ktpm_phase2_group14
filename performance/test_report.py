"""Small offline check for the CSV-to-report converter."""

import csv
import json
import tempfile
import unittest
from pathlib import Path

from performance.report import build_report


class ReportTest(unittest.TestCase):
    def test_reads_aggregate_and_writes_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            metadata = {
                "started_at_utc": "2026-09-24T00:00:00+00:00",
                "git_commit": "example-sha", "environment": "test fixture",
                "cpu_count": 4, "memory_gb": 30, "java_version": "21",
                "mysql_version": "8.4", "locust_version": "2.32.1",
                "phases": {"smoke": {"users": 5, "duration_seconds": 60, "exit_code": 0}},
            }
            (run_dir / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            with (run_dir / "smoke_stats.csv").open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=[
                    "Name", "Request Count", "Failure Count", "Requests/s",
                    "50%", "95%", "99%",
                ])
                writer.writeheader()
                writer.writerow({"Name": "Aggregated", "Request Count": 100,
                                 "Failure Count": 1, "Requests/s": 10,
                                 "50%": 20, "95%": 50, "99%": 90})
            result = build_report(run_dir)
            self.assertEqual(result[0]["failure_percent"], 1)
            self.assertEqual(result[0]["p95_ms"], 50)
            self.assertTrue((run_dir / "summary.csv").is_file())
            self.assertIn("example-sha", (run_dir / "REPORT.md").read_text(encoding="utf-8"))
            report = (run_dir / "REPORT.md").read_text(encoding="utf-8")
            self.assertIn("Chưa có api.log", report)
            self.assertNotIn("Nhận xét cần hoàn thiện", report)
            (run_dir / "api.log").write_text("INFO started\nERROR database failed\nTestException: fixture\n", encoding="utf-8")
            build_report(run_dir)
            self.assertIn("2 dòng có mẫu ERROR/Exception", (run_dir / "REPORT.md").read_text(encoding="utf-8"))

    def test_hotspot_report_shows_integrity_and_lock_contention(self):
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            phases = {}
            for phase, ok in (("hotspot_10", True), ("oversell_100", False)):
                phases[phase] = {
                    "users": 10, "duration_seconds": 60, "exit_code": 1,
                    "innodb": {"Innodb_row_lock_waits": 42, "Innodb_row_lock_time": 1234,
                               "lock_timeouts": 0, "lock_deadlocks": 0},
                    "integrity": {"initial_stock": 300, "sold": 300, "orders_created": 300,
                                  "locust_201": 299, "stock_after": 0, "movements_sum": 0, "ok": ok},
                }
                with (run_dir / f"{phase}_stats.csv").open("w", encoding="utf-8", newline="") as stream:
                    writer = csv.DictWriter(stream, fieldnames=[
                        "Name", "Request Count", "Failure Count", "Requests/s", "50%", "95%", "99%",
                    ])
                    writer.writeheader()
                    writer.writerow({"Name": "Aggregated", "Request Count": 400, "Failure Count": 100,
                                     "Requests/s": 40, "50%": 30, "95%": 80, "99%": 120})
            (run_dir / "metadata.json").write_text(json.dumps({
                "started_at_utc": "2026-11-20T00:00:00+00:00", "git_commit": "hot-sha",
                "environment": "test fixture", "scenario": "hotspot", "cpu_count": 4,
                "memory_gb": 30, "java_version": "21", "mysql_version": "8.0",
                "locust_version": "2.32.1", "phases": phases}), encoding="utf-8")
            build_report(run_dir)
            report = (run_dir / "REPORT.md").read_text(encoding="utf-8")
            self.assertIn("Kịch bản: `hotspot`", report)
            self.assertIn("| hotspot_10 | 300 | 300 | 300 | 299 | 0 | 0 | OK |", report)
            self.assertIn("| oversell_100 | 300 | 300 | 300 | 299 | 0 | 0 | FAIL |", report)
            self.assertIn("| hotspot_10 | 42 | 1234 | 0 | 0 |", report)
            self.assertNotIn("60% danh sách sản phẩm", report)

    def test_declared_phase_without_csv_is_not_silently_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            (run_dir / "metadata.json").write_text(json.dumps({
                "phases": {"smoke": {"users": 5, "duration_seconds": 60}}}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "without its Locust CSV"):
                build_report(run_dir)


if __name__ == "__main__":
    unittest.main()
