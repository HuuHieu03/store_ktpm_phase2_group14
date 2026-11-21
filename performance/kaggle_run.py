"""Run a reproducible Single Store Team load test inside a fresh Kaggle CPU notebook.

This deliberately refuses to run outside Kaggle or against an existing schema.
It starts MySQL and the Spring Boot JAR directly; Docker is not required there.
"""

import base64
import csv
import json
import os
import platform
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

from report import build_report


REPO = Path(__file__).resolve().parent.parent
WORKING = Path("/kaggle/working")
HOST = "http://127.0.0.1:18080"
PHASES = (
    ("smoke", 5, 60),
    ("baseline", 10, 60),
    ("main", 30, 90),
    ("high", 60, 90),
    ("stress_100", 100, 90),
    ("stress_150", 150, 90),
    ("stress_200", 200, 90),
    ("stress_250", 250, 90),
    ("stress_300", 300, 90),
    ("stress_400", 400, 90),
    ("stress_500", 500, 90),
)
# Every user buys the same SKU without think time; a fresh hot SKU is created per phase.
HOTSPOT_PHASES = (
    ("hotspot_10", 10, 60),
    ("hotspot_50", 50, 60),
    ("hotspot_100", 100, 90),
    ("hotspot_200", 200, 90),
    ("oversell_100", 100, 60),
)
HOT_STOCK = {"oversell_100": 300}
DEFAULT_HOT_STOCK = 1_000_000
SOAK_PHASES = (
    ("soak_300", 300, 1800),
)
SCENARIOS = {
    "stress": {"locustfile": "performance/locustfile.py", "phases": PHASES},
    "hotspot": {"locustfile": "performance/locustfile_hotspot.py", "phases": HOTSPOT_PHASES},
    "soak": {"locustfile": "performance/locustfile.py", "phases": SOAK_PHASES},
}
INNODB_STATUS = ("Innodb_row_lock_waits", "Innodb_row_lock_time", "Innodb_row_lock_time_max")
INNODB_METRICS = ("lock_deadlocks", "lock_timeouts")


def run(command, **kwargs):
    print("Running:", " ".join(str(part) for part in command), flush=True)
    return subprocess.run(command, check=True, cwd=REPO, **kwargs)


def output(command):
    return subprocess.check_output(command, cwd=REPO, text=True, stderr=subprocess.STDOUT).strip()


def mysql(sql):
    return output(["mysql", "-uroot", "-N", "-e", sql])


def prepare_system():
    if not WORKING.is_dir() or getattr(os, "geteuid", lambda: -1)() != 0 or platform.system() != "Linux":
        raise RuntimeError("Only run this script as root inside a Kaggle Linux CPU notebook")
    run(["apt-get", "update", "-qq"])
    run(["apt-get", "install", "-y", "mysql-server", "openjdk-21-jdk-headless"],
        env={**os.environ, "DEBIAN_FRONTEND": "noninteractive"})
    java_home = Path("/usr/lib/jvm/java-21-openjdk-amd64")
    if not (java_home / "bin/java").exists():
        raise RuntimeError("JDK 21 not installed at expected Ubuntu path; inspect java -version")
    os.environ["JAVA_HOME"] = str(java_home)
    os.environ["PATH"] = f"{java_home / 'bin'}:{os.environ['PATH']}"
    run([sys.executable, "-m", "pip", "install", "locust==2.32.1"])
    run(["service", "mysql", "start"])


def prepare_mysql():
    version = mysql("SELECT VERSION()")
    if not version.startswith("8."):
        raise RuntimeError(f"Expected MySQL 8.x, found {version}")
    database_password = secrets.token_urlsafe(24)
    mysql("CREATE DATABASE IF NOT EXISTS single_store_team CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
    existing = int(mysql("SELECT COUNT(*) FROM information_schema.tables "
                         "WHERE table_schema='single_store_team'"))
    if existing:
        raise RuntimeError("single_store_team is not empty; use a fresh Kaggle session")
    for host in ("localhost", "127.0.0.1"):
        mysql(f"CREATE USER IF NOT EXISTS 'load_user'@'{host}' IDENTIFIED BY '{database_password}'; "
              f"GRANT ALL PRIVILEGES ON single_store_team.* TO 'load_user'@'{host}'")
    return database_password, version


def start_api(run_dir, database_password):
    run(["bash", "./mvnw", "-B", "-Dmaven.test.skip=true", "package"])
    jars = [item for item in (REPO / "target").glob("single-store-service-*.jar")
            if not item.name.endswith(".original")]
    if len(jars) != 1:
        raise RuntimeError(f"Expected one Spring Boot JAR, found {jars}")
    admin_email = f"load-admin-{secrets.token_hex(5)}@example.com"
    admin_password = "Aa1!" + secrets.token_urlsafe(16)
    jwt_secret = base64.b64encode(secrets.token_bytes(48)).decode("ascii")
    env = {**os.environ,
           "SPRING_PROFILES_ACTIVE": "mysql", "SERVER_PORT": "18080",
           "DB_URL": "jdbc:mysql://127.0.0.1:3306/single_store_team?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
           "DB_USERNAME": "load_user", "DB_PASSWORD": database_password,
           "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate",
           "BOOTSTRAP_ADMIN_EMAIL": admin_email,
           "BOOTSTRAP_ADMIN_PASSWORD": admin_password,
           "JWT_SECRET": jwt_secret}
    api_log = (run_dir / "api.log").open("w", encoding="utf-8")
    process = subprocess.Popen([str(Path(os.environ["JAVA_HOME"]) / "bin/java"), "-jar", str(jars[0])],
                               cwd=REPO, env=env, stdout=api_log, stderr=subprocess.STDOUT)
    try:
        for _ in range(90):
            if process.poll() is not None:
                raise RuntimeError(f"API exited early; inspect {run_dir / 'api.log'}")
            try:
                with urllib.request.urlopen(HOST + "/api/products", timeout=2) as response:
                    if response.status == 200:
                        break
            except (urllib.error.URLError, TimeoutError):
                pass
            time.sleep(2)
        else:
            raise RuntimeError(f"API not ready after 180 seconds; inspect {run_dir / 'api.log'}")
        versions = mysql("SELECT GROUP_CONCAT(version ORDER BY installed_rank) "
                         "FROM single_store_team.flyway_schema_history WHERE success=1")
        if versions != "1,2":
            raise RuntimeError(f"Unexpected Flyway versions: {versions}")
        tables = int(mysql("SELECT COUNT(*) FROM information_schema.tables "
                           "WHERE table_schema='single_store_team' AND table_name IN "
                           "('users','products','orders','order_items','cart_items','inventory_movements')"))
        if tables != 6:
            raise RuntimeError(f"Expected six business tables, got {tables}")
        return process, api_log, admin_email, admin_password
    except Exception:
        process.terminate()
        process.wait(timeout=15)
        api_log.close()
        raise


def memory_gb():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemTotal:"):
            return round(int(line.split()[1]) / 1024 / 1024, 2)
    return "unknown"


def api_post(path, body, expected=200, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(HOST + path, data=json.dumps(body).encode("utf-8"),
                                     headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != expected:
                raise RuntimeError(f"Setup {path}: expected {expected}, got {response.status}")
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Setup {path} returned {error.code}: "
                           f"{error.read(300).decode('utf-8', errors='replace')}") from error


def prepare_fixtures(target_count, pool, admin_token, public_product_ids):
    needed = target_count - len(pool)
    for _ in range(needed):
        suffix = uuid.uuid4().hex[:16]
        email = f"load-{suffix}@example.com"
        password = "LoadCustomer@123"
        api_post("/api/auth/register", {
            "fullName": "Load test customer", "email": email, "password": password,
        }, expected=201)
        token = api_post("/api/auth/login", {
            "email": email, "password": password,
        })["accessToken"]
        product = api_post("/api/products", {
            "sku": f"LOAD-{suffix}", "name": "Sản phẩm tải riêng",
            "description": "Chỉ dùng trong DB kiểm thử tải",
            "price": 1000, "stockQuantity": 100000,
        }, expected=201, token=admin_token)
        pool.append({"email": email, "password": password, "token": token,
                     "product_id": product["id"], "public_product_ids": public_product_ids})
    return pool[:target_count]


def prepare_hot_product(phase, initial_stock, admin_token):
    product = api_post("/api/products", {
        "sku": f"HOT-{phase}-{uuid.uuid4().hex[:8]}".upper(), "name": f"Sản phẩm nóng {phase}",
        "description": "SKU dùng chung cho kịch bản hotspot",
        "price": 1000, "stockQuantity": initial_stock,
    }, expected=201, token=admin_token)
    return product["id"]


def innodb_counters():
    names = ",".join(f"'{name}'" for name in INNODB_STATUS)
    counters = {}
    for line in mysql(f"SHOW GLOBAL STATUS WHERE Variable_name IN ({names})").splitlines():
        name, value = line.split("\t")
        counters[name] = int(value)
    metric_names = ",".join(f"'{name}'" for name in INNODB_METRICS)
    try:
        for line in mysql("SELECT NAME, COUNT FROM information_schema.INNODB_METRICS "
                          f"WHERE NAME IN ({metric_names})").splitlines():
            name, value = line.split("\t")
            counters[name] = int(value)
    except subprocess.CalledProcessError:
        pass
    return counters


def innodb_delta(before, after):
    delta = {name: after[name] - before.get(name, 0) for name in after
             if name != "Innodb_row_lock_time_max"}
    if "Innodb_row_lock_time_max" in after:
        delta["Innodb_row_lock_time_max_cumulative"] = after["Innodb_row_lock_time_max"]
    return delta


def locust_successes(run_dir, phase):
    with (run_dir / f"{phase}_stats.csv").open(encoding="utf-8-sig", newline="") as stream:
        aggregate = next(row for row in csv.DictReader(stream)
                         if row.get("Name", "").lower().startswith("aggregat"))
    return int(float(aggregate["Request Count"])) - int(float(aggregate["Failure Count"]))


def wait_for_orders_to_settle(product_id, stable_polls=3, timeout=120):
    """Server keeps committing queued orders after Locust stops; wait until the count is stable."""
    query = ("SELECT COUNT(*) FROM single_store_team.order_items "
             f"WHERE product_id={int(product_id)}")
    started = time.monotonic()
    previous, stable = None, 0
    while time.monotonic() - started < timeout:
        current = int(mysql(query))
        stable = stable + 1 if current == previous else 0
        if stable >= stable_polls:
            return round(time.monotonic() - started, 1)
        previous = current
        time.sleep(1)
    return None


def hot_product_integrity(product_id, initial_stock, locust_success, settle_seconds):
    pid = int(product_id)
    # One statement reads one InnoDB snapshot, so the three numbers cannot drift apart.
    stock_after, sold, orders, movements_sum = (int(value) for value in mysql(
        "SELECT p.stock_quantity, "
        "(SELECT COALESCE(SUM(oi.quantity),0) FROM single_store_team.order_items oi "
        " JOIN single_store_team.orders o ON o.id=oi.order_id "
        " WHERE oi.product_id=p.id AND o.status<>'CANCELLED'), "
        "(SELECT COUNT(DISTINCT oi.order_id) FROM single_store_team.order_items oi "
        " JOIN single_store_team.orders o ON o.id=oi.order_id "
        " WHERE oi.product_id=p.id AND o.status<>'CANCELLED'), "
        "(SELECT COALESCE(SUM(m.quantity_change),0) FROM single_store_team.inventory_movements m "
        " WHERE m.product_id=p.id) "
        f"FROM single_store_team.products p WHERE p.id={pid}").split())
    ok = (initial_stock - sold == stock_after and movements_sum == stock_after
          and stock_after >= 0 and sold <= initial_stock)
    return {"initial_stock": initial_stock, "sold": sold, "orders_created": orders,
            "stock_after": stock_after, "movements_sum": movements_sum,
            "locust_201": locust_success, "settle_seconds": settle_seconds, "ok": ok}


class ResourceSampler(threading.Thread):
    """Sample CPU% and RSS of API, MySQL and Locust every interval into a CSV."""

    def __init__(self, path, pids, interval=2.0):
        super().__init__(daemon=True)
        import psutil
        self.psutil = psutil
        self.path = path
        self.interval = interval
        self.stopped = threading.Event()
        self.processes = {}
        for label, pid in pids.items():
            try:
                self.processes[label] = psutil.Process(pid)
            except psutil.Error:
                pass
        mysqld = next((proc for proc in psutil.process_iter(["name"])
                       if proc.info["name"] == "mysqld"), None)
        if mysqld is not None:
            self.processes["mysqld"] = mysqld

    def run(self):
        labels = list(self.processes)
        fields = ["elapsed_s", "system_cpu_percent"]
        for label in labels:
            fields += [f"{label}_cpu_percent", f"{label}_rss_mb"]
        for proc in self.processes.values():
            proc.cpu_percent(None)
        self.psutil.cpu_percent(None)
        started = time.monotonic()
        with self.path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            while not self.stopped.wait(self.interval):
                row = {"elapsed_s": round(time.monotonic() - started, 1),
                       "system_cpu_percent": self.psutil.cpu_percent(None)}
                for label in labels:
                    try:
                        proc = self.processes[label]
                        row[f"{label}_cpu_percent"] = proc.cpu_percent(None)
                        row[f"{label}_rss_mb"] = round(proc.memory_info().rss / 1024 / 1024, 1)
                    except self.psutil.Error:
                        row[f"{label}_cpu_percent"] = ""
                        row[f"{label}_rss_mb"] = ""
                writer.writerow(row)
                stream.flush()

    def stop(self):
        self.stopped.set()
        self.join(timeout=10)


def start_sampler(run_dir, phase, pids, metadata):
    try:
        sampler = ResourceSampler(run_dir / f"{phase}_resources.csv", pids)
    except ImportError:
        metadata["resource_sampling"] = "psutil not installed; no *_resources.csv"
        return None
    sampler.start()
    return sampler


def run_phases(run_dir, metadata, admin_email, admin_password, api_process, scenario):
    admin_token = api_post("/api/auth/login", {
        "email": admin_email, "password": admin_password,
    })["accessToken"]
    with urllib.request.urlopen(HOST + "/api/products?page=0&size=20", timeout=30) as response:
        public_product_ids = [item["id"] for item in json.load(response)["items"]]
    if not public_product_ids:
        raise RuntimeError("No products available for detail GET task")
    only_smoke = os.environ.get("LOAD_SMOKE_ONLY") == "1"
    locustfile = REPO / SCENARIOS[scenario]["locustfile"]
    fixture_pool = []
    for index, (phase, users, seconds) in enumerate(SCENARIOS[scenario]["phases"]):
        if only_smoke and index > 0:
            break
        if api_process.poll() is not None:
            metadata["stopped_reason"] = f"API process crashed before phase {phase}"
            (run_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
            break
        hot_product_id = None
        initial_stock = HOT_STOCK.get(phase, DEFAULT_HOT_STOCK)
        try:
            fixtures = prepare_fixtures(users, fixture_pool, admin_token, public_product_ids)
            if scenario == "hotspot":
                hot_product_id = prepare_hot_product(phase, initial_stock, admin_token)
                fixtures = [{**fixture, "product_id": hot_product_id} for fixture in fixtures]
        except Exception as exc:
            metadata["stopped_reason"] = f"Failed to prepare fixtures for {phase} ({users} users): {exc}"
            (run_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
            break
        prefix = run_dir / phase
        spawn_rate = max(5, users // 10)
        command = [sys.executable, "-m", "locust", "-f", str(locustfile),
                   "--headless", "--host", HOST, "-u", str(users), "-r", str(spawn_rate),
                   "-t", f"{seconds}s", "--csv", str(prefix), "--csv-full-history",
                   "--html", str(run_dir / f"{phase}_report.html")]
        print(f"Starting {phase}: {users} users for {seconds}s (spawn {spawn_rate}/s)", flush=True)
        innodb_before = innodb_counters()
        fixture_path = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".json",
                                             prefix="single-store-load-fixtures-", delete=False) as fixture_file:
                json.dump(fixtures, fixture_file)
                fixture_path = Path(fixture_file.name)
            env = {**os.environ, "LOAD_FIXTURES_PATH": str(fixture_path)}
            with (run_dir / f"{phase}_locust.log").open("w", encoding="utf-8") as log:
                locust = subprocess.Popen(command, cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT)
                sampler = start_sampler(run_dir, phase, {"api": api_process.pid, "locust": locust.pid},
                                        metadata)
                try:
                    returncode = locust.wait(timeout=seconds + 120)
                except subprocess.TimeoutExpired:
                    locust.kill()
                    locust.wait()
                    raise
                finally:
                    if sampler is not None:
                        sampler.stop()
        finally:
            if fixture_path is not None:
                fixture_path.unlink(missing_ok=True)
        phase_metadata = {"users": users, "duration_seconds": seconds, "exit_code": returncode}
        if hot_product_id is not None:
            settle_seconds = wait_for_orders_to_settle(hot_product_id)
            phase_metadata["hot_product_id"] = hot_product_id
            phase_metadata["integrity"] = hot_product_integrity(
                hot_product_id, initial_stock, locust_successes(run_dir, phase), settle_seconds)
        phase_metadata["innodb"] = innodb_delta(innodb_before, innodb_counters())
        metadata["phases"][phase] = phase_metadata
        (run_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        results = build_report(run_dir)
        phase_result = next((item for item in results if item["phase"] == phase), None)
        if phase_result:
            print(f"Phase {phase} finished: RPS={phase_result['rps']}, failures={phase_result['failures']} ({phase_result['failure_percent']}%), p95={phase_result['p95_ms']}ms", flush=True)
            if "integrity" in phase_metadata:
                print(f"Phase {phase} integrity: {phase_metadata['integrity']}", flush=True)
            if scenario == "stress" and phase_result["failure_percent"] >= 40.0:
                metadata["breakpoint_reason"] = f"Breakpoint crossed: failure rate {phase_result['failure_percent']}% >= 40% at phase {phase} ({users} users)"
                (run_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
                print(f"Stopping stress ladder: {metadata['breakpoint_reason']}", flush=True)
                break


def main():
    if not WORKING.is_dir() or getattr(os, "geteuid", lambda: -1)() != 0 or platform.system() != "Linux":
        raise RuntimeError("Only run this script as root inside a Kaggle Linux CPU notebook")
    scenario = os.environ.get("LOAD_SCENARIO", "stress")
    if scenario not in SCENARIOS:
        raise ValueError(f"LOAD_SCENARIO must be one of {sorted(SCENARIOS)}, got {scenario!r}")
    if output(["git", "status", "--porcelain"]):
        raise RuntimeError("Commit source changes before benchmarking; use a clean Git checkout")
    run_dir = WORKING / "single_store_load" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_dir.mkdir(parents=True, exist_ok=False)
    print(f"Evidence directory: {run_dir}", flush=True)
    process = None
    api_log = None
    try:
        prepare_system()
        database_password, mysql_version = prepare_mysql()
        process, api_log, admin_email, admin_password = start_api(run_dir, database_password)
        metadata = {
            "started_at_utc": datetime.now(timezone.utc).isoformat(),
            "environment": "Kaggle CPU Notebook", "scenario": scenario,
            "git_commit": output(["git", "rev-parse", "HEAD"]),
            "cpu_count": os.cpu_count(), "memory_gb": memory_gb(),
            "java_version": output(["java", "-version"]).splitlines()[0],
            "mysql_version": mysql_version,
            "locust_version": output([sys.executable, "-m", "locust", "--version"]),
            "phases": {},
        }
        (run_dir / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        run_phases(run_dir, metadata, admin_email, admin_password, process, scenario)
        print(f"Finished. Download {run_dir} (CSV, HTML, logs, REPORT.md).", flush=True)
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if api_log is not None:
            api_log.close()


if __name__ == "__main__":
    main()
