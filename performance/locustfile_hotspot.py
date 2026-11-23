"""Hotspot workload: every virtual user buys the same SKU with no think time.

Fixtures are prepared by kaggle_run.py; all of them point at one hot product per phase.
Non-201 responses are recorded as failures grouped by reason so *_failures.csv shows
lock conflicts, out-of-stock rejections and server errors separately.
"""

import itertools
import json
import os
from pathlib import Path

from locust import HttpUser, constant, task


_FIXTURES = json.loads(Path(os.environ["LOAD_FIXTURES_PATH"]).read_text(encoding="utf-8"))
_NEXT_FIXTURE = itertools.count()


def classify(status_code, body):
    """Map a non-201 order response to a stable failure reason."""
    try:
        error = json.loads(body) if body else {}
    except ValueError:
        error = {}
    code = error.get("code", "") if isinstance(error, dict) else ""
    message = error.get("message", "") if isinstance(error, dict) else ""
    if status_code == 409 and code == "CONCURRENT_CHANGE":
        return "lock_conflict"
    if status_code == 409 and "tồn kho" in message:
        return "out_of_stock"
    if status_code >= 500:
        return f"server_error {status_code} {code}".strip()
    return f"unexpected {status_code} {code}".strip()


class HotspotBuyer(HttpUser):
    wait_time = constant(0)

    def on_start(self):
        index = next(_NEXT_FIXTURE)
        if index >= len(_FIXTURES):
            raise RuntimeError("Not enough prepared users for Locust -u")
        fixture = _FIXTURES[index]
        self.token = fixture["token"]
        self.hot_product_id = fixture["product_id"]

    @task
    def buy_hot_product(self):
        body = {
            "recipientName": "Khách hotspot",
            "recipientPhone": "0901234567",
            "shippingAddress": "1 Đường kiểm thử, Hà Nội",
            "items": [{"productId": self.hot_product_id, "quantity": 1}],
        }
        with self.client.post("/api/orders", json=body, timeout=30,
                              headers={"Authorization": f"Bearer {self.token}"},
                              name="POST /api/orders (hotspot)", catch_response=True) as response:
            # Status 0 means timeout/connection error: keep Locust's own exception message.
            if response.status_code not in (0, 201):
                response.failure(classify(response.status_code, response.text))
