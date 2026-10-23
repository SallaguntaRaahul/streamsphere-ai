"""Failure-injection drills against the live local stack. Each drill injects a real fault with Docker,
asserts the documented recovery behaviour, and records timings. Results → docs/benchmarks/."""
import json, os, subprocess, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "e2e"))
from ss_api import *

FIX = os.path.join(ROOT, "tests", "fixtures", "media")
LONG = os.path.join(ROOT, "tmp", "drill-90s.mp4")
report = []


def dc(*args):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout


def record(name, ok, detail, seconds):
    report.append({"drill": name, "result": "PASS" if ok else "FAIL", "detail": detail, "seconds": round(seconds, 1)})
    print(("PASS " if ok else "FAIL ") + name + f" ({seconds:.1f}s): {detail}")


def gen_long():
    if not os.path.exists(LONG):
        subprocess.run(["docker", "run", "--rm", "--entrypoint", "ffmpeg", "-u", "10001", "--tmpfs", "/tmp", "-v", f"{os.path.dirname(LONG)}:/out",
                        "streamsphere/ffmpeg-worker:local", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                        "-f", "lavfi", "-i", "sine=frequency=330", "-t", "90", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                        "/out/drill-90s.mp4"], check=True)


def drill_worker_killed_mid_encode():
    gen_long()
    t0 = time.time()
    t = new_title("creator", f"Drill worker crash {int(t0)}")
    s, p = upload_file("creator", t["id"], LONG)
    expect(call("POST", f"/api/v1/uploads/{s['id']}/complete", "creator", {"parts": p}), 200)
    wait_until(lambda: (call("GET", f"/api/v1/media/titles/{t['id']}/assets", "creator").json() or [{}])[0].get("jobs", [{}])[0].get("state") == "RUNNING", 60, 0.5, "job RUNNING")
    time.sleep(3)
    dc("kill", "ffmpeg-worker")          # SIGKILL mid-encode: no result is ever published
    time.sleep(2)
    dc("start", "ffmpeg-worker")         # unacked request is redelivered to the restarted worker
    a = wait_asset("creator", t["id"], timeout=600)
    ok = a["asset"]["state"] == "READY"
    record("FFmpeg worker SIGKILL mid-encode → redelivery → READY", ok,
           f"state={a['asset']['state']} jobs={[ (j['attempt'], j['state']) for j in a['jobs']]} renditions={len(a['renditions'])}", time.time() - t0)


def drill_rabbit_restart():
    t0 = time.time()
    dc("restart", "rabbitmq")
    dc("up", "-d", "--wait", "rabbitmq")
    t = new_title("creator", f"Drill rabbit restart {int(t0)}")
    s, p = upload_file("creator", t["id"], os.path.join(FIX, "fixture-360p-8s-noaudio.mp4"))
    expect(call("POST", f"/api/v1/uploads/{s['id']}/complete", "creator", {"parts": p}), 200)
    a = wait_asset("creator", t["id"], timeout=180)
    record("RabbitMQ restart → consumers/relays reconnect → pipeline completes", a["asset"]["state"] == "READY", f"state={a['asset']['state']}", time.time() - t0)


def drill_object_store_outage():
    t0 = time.time()
    t = new_title("creator", f"Drill storage outage {int(t0)}")
    dc("stop", "minio")
    try:
        r = call("POST", "/api/v1/uploads", "creator", {"titleId": t["id"], "fileName": "a.mp4", "contentType": "video/mp4", "sizeBytes": 1000, "sha256": "a" * 64})
        during = r.status
    finally:
        dc("start", "minio")
        dc("up", "-d", "--wait", "minio")
    back = time.time()
    body = {"titleId": t["id"], "fileName": "a.mp4", "contentType": "video/mp4", "sizeBytes": 1000, "sha256": "a" * 64}
    wait_until(lambda: call("POST", "/api/v1/uploads", "creator", body).status == 201, 60, 0.5, "uploads accepted again")
    recovery = time.time() - back
    s, p = upload_file("creator", t["id"], os.path.join(FIX, "fixture-360p-8s-noaudio.mp4"))
    after = call("POST", f"/api/v1/uploads/{s['id']}/complete", "creator", {"parts": p}).status
    record("Object store outage → 503 storage-unavailable (no partial state) → recovers", during == 503 and after == 200,
           f"during outage HTTP {during}; uploads accepted {recovery:.1f}s after storage healthy; complete HTTP {after}", time.time() - t0)


def drill_cache_outage():
    t0 = time.time()
    dc("stop", "valkey")
    try:
        browse = call("GET", "/api/v1/titles?limit=5").status
        some = call("GET", "/api/v1/titles?limit=1").json()["items"][0]["id"]
        ps = call("POST", "/api/v1/playback/sessions", "viewer", {"titleId": some})
        seg = call("GET", ps.json()["manifestUrl"]).status if ps.status == 201 else ps.status
    finally:
        dc("start", "valkey")
    record("Valkey outage → catalog + playback authorization fall back to PostgreSQL", browse == 200 and ps.status == 201 and seg == 200,
           f"browse {browse}, playback session {ps.status}, master playlist {seg}", time.time() - t0)


def drill_service_restart():
    t0 = time.time()
    dc("restart", "catalog")
    errors, first_ok = 0, None
    for _ in range(120):
        r = call("GET", "/api/v1/titles?limit=1")
        if r.status == 200:
            first_ok = time.time() - t0
            break
        errors += 1
        time.sleep(0.5)
    record("Catalog pod restart → gateway returns errors briefly, then recovers", first_ok is not None,
           f"recovered after {first_ok and round(first_ok,1)} s with {errors} failed requests (single replica: no HA claimed)", time.time() - t0)


if __name__ == "__main__":
    ensure_creator("creator", "Cyrus Films")
    for d in (drill_cache_outage, drill_object_store_outage, drill_rabbit_restart, drill_service_restart, drill_worker_killed_mid_encode):
        try:
            d()
        except Exception as e:
            record(d.__name__, False, f"exception: {e}", 0)
    json.dump(report, open(os.path.join(ROOT, "tmp", "drills.json"), "w"), indent=1)
    sys.exit(0 if all(r["result"] == "PASS" for r in report) else 1)
