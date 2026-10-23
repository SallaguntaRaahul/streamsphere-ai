"""E2E: creator upload → verify → FFmpeg ABR HLS → captions → authorized preview playback."""
import os, sys, time
sys.path.insert(0, os.path.dirname(__file__))
from ss_api import *

FIX = os.path.join(ROOT, "tests", "fixtures", "media")
results = []

def step(name, fn):
    t = time.time()
    try:
        out = fn()
        results.append(("PASS", name, round(time.time() - t, 2)))
        print(f"PASS  {name} ({time.time() - t:.1f}s)")
        return out
    except Exception as e:
        results.append(("FAIL", name, str(e)[:300]))
        print(f"FAIL  {name}: {e}")
        raise

ensure_creator("creator", "Cyrus Films")
title = step("create draft + rights attestation", lambda: new_title("creator", f"Pipeline Test {int(time.time())}"))
tid = title["id"]
session, parts = step("multipart upload direct to object storage (presigned)", lambda: upload_file("creator", tid, os.path.join(FIX, "fixture-720p-20s.mp4"), idem=f"e2e-{tid}"))
step("idempotent create returns same session", lambda: (
    expect(call("POST", "/api/v1/uploads", "creator", {"titleId": tid, "fileName": "x.mp4", "contentType": "video/mp4", "sizeBytes": session["sizeBytes"], "sha256": session["sha256"]},
                headers={"Idempotency-Key": f"e2e-{tid}"}), 201).json()["id"] == session["id"]) or (_ for _ in ()).throw(AssertionError("different session")))
done = step("complete + server-side verification", lambda: expect(call("POST", f"/api/v1/uploads/{session['id']}/complete", "creator", {"parts": parts}), 200).json())
assert done["state"] == "UPLOADED", done
step("duplicate completion is idempotent", lambda: expect(call("POST", f"/api/v1/uploads/{session['id']}/complete", "creator", {"parts": parts}), 200))
step("other creator cannot read upload (IDOR)", lambda: expect(call("GET", f"/api/v1/uploads/{session['id']}", "creator2"), 404))
asset = step("FFmpeg transcode to ABR HLS (READY)", lambda: wait_asset("creator", tid))
assert asset["asset"]["state"] == "READY", asset
names = [r["name"] for r in asset["renditions"]]
step("ladder 360p/480p/720p, no upscale", lambda: names == ["360p", "480p", "720p"] or (_ for _ in ()).throw(AssertionError(names)))
print("   renditions:", [(r["name"], r["width"], r["height"], r["segmentCount"], r["bytes"]) for r in asset["renditions"]],
      "job:", [(j["state"], j["wallSeconds"], j["cpuSeconds"]) for j in asset["jobs"]])
aid = asset["asset"]["id"]
vtt = open(os.path.join(FIX, "fixture.en.vtt")).read()
step("add WebVTT captions", lambda: expect(call("POST", f"/api/v1/media/assets/{aid}/captions", "creator", {"language": "en", "label": "English", "isDefault": True, "webvtt": vtt}), 201))
step("viewer cannot play unpublished title", lambda: expect(call("POST", "/api/v1/playback/sessions", "viewer", {"titleId": tid}), 404))
ps = step("owner preview playback session", lambda: expect(call("POST", "/api/v1/playback/sessions", "creator", {"titleId": tid}), 201).json())
master = step("master playlist (authorized)", lambda: expect(call("GET", ps["manifestUrl"]), 200).body)
assert "#EXT-X-STREAM-INF" in master and "SUBTITLES=\"subs\"" in master, master
print("   master:\n   " + master.replace("\n", "\n   ").strip())
base = ps["manifestUrl"].rsplit("/", 1)[0]
variant = step("variant playlist", lambda: expect(call("GET", base + "/v/360p/index.m3u8"), 200).body)
seg = [l for l in variant.splitlines() if l.endswith(".ts")][0]
loc = step("segment → 302 short-lived presigned URL", lambda: expect(call("GET", base + "/v/360p/" + seg, follow=False), 302).headers["Location"])
assert "X-Amz-Expires=60" in loc, loc
data = step("segment bytes are MPEG-TS", lambda: expect(call("GET", loc, raw=True), 200).body)
assert data[0] == 0x47, data[:4]
step("subtitle playlist + vtt", lambda: "WEBVTT" in expect(call("GET", base + "/subs/en.vtt"), 200).body)
step("path traversal rejected", lambda: expect(call("GET", base + "/v/360p/..%2F..%2Fsecret.ts"), 404, 400))
step("tampered token rejected", lambda: expect(call("GET", base[:-3] + "AAA/master.m3u8"), 403))
step("direct bucket access denied (private)", lambda: expect(call("GET", "http://localhost:9000/streamsphere-media/" + asset["asset"]["outputPrefix"] + "360p/" + seg), 403))
step("revoke session → next segment 403", lambda: (expect(call("POST", f"/api/v1/playback/sessions/{ps['sessionId']}/revoke", "creator"), 204),
                                                    expect(call("GET", base + "/v/360p/" + seg, follow=False), 403)))
print(f"\n{sum(r[0]=='PASS' for r in results)}/{len(results)} passed; title={tid} asset={aid}")
