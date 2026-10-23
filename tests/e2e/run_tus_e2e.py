"""E2E: tus 1.0 resumable upload (tusd + upload-service hooks) → verification → FFmpeg → READY.
Includes an interrupted upload resumed via HEAD offset discovery, and admission failures."""
import base64, os, sys, time, urllib.request, urllib.error
sys.path.insert(0, os.path.dirname(__file__))
from ss_api import *

TUS = os.environ.get("TUS_URL", "http://localhost:1080/files/")
FIX = os.path.join(ROOT, "tests", "fixtures", "media", "fixture-720p-20s.mp4")
results = []


def req(method, url, user=None, headers=None, body=None):
    h = {"Tus-Resumable": "1.0.0", **(headers or {})}
    if user:
        h["Authorization"] = "Bearer " + token(user)
    r = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()


def meta(**kv):
    return ",".join(f"{k} {base64.b64encode(str(v).encode()).decode()}" for k, v in kv.items())


def step(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS  " if ok else "FAIL  ") + name + (f" — {detail}" if detail else ""))


ensure_creator("creator", "Cyrus Films")
ensure_creator("creator2", "Chloe Studio")
t = new_title("creator", f"tus upload {int(time.time())}")
data = open(FIX, "rb").read()
md = meta(titleId=t["id"], filename="fixture.mp4", filetype="video/mp4", sha256=sha256(FIX))

st, h, _ = req("POST", TUS, None, {"Upload-Length": str(len(data)), "Upload-Metadata": md})
step("anonymous create rejected", st == 401, f"HTTP {st}")
st, h, _ = req("POST", TUS, "creator2", {"Upload-Length": str(len(data)), "Upload-Metadata": md})
step("other creator's title rejected (BOLA)", st == 404, f"HTTP {st}")
st, h, _ = req("POST", TUS, "creator", {"Upload-Length": str(10**12), "Upload-Metadata": md})
step("oversized rejected", st == 413, f"HTTP {st}")

st, h, _ = req("POST", TUS, "creator", {"Upload-Length": str(len(data)), "Upload-Metadata": md})
loc = h.get("location", "")
step("create accepted (pre-create hook admitted)", st == 201 and loc, f"HTTP {st} {loc}")
url = loc if loc.startswith("http") else "http://localhost:1080" + loc

half = 5 * 1024 * 1024 + 123
st, h, _ = req("PATCH", url, "creator", {"Upload-Offset": "0", "Content-Type": "application/offset+octet-stream"}, data[:half])
step("first chunk stored, then 'connection lost'", st == 204, f"offset {h.get('upload-offset')}")
st, h, _ = req("HEAD", url, "creator")
off = int(h.get("upload-offset", "-1"))
step("resume: HEAD reports server offset", off == half, f"offset {off}")
st, h, _ = req("PATCH", url, "creator", {"Upload-Offset": str(off), "Content-Type": "application/offset+octet-stream"}, data[off:])
step("remaining bytes accepted (post-finish hook verified + published)", st == 204, f"HTTP {st} final offset {h.get('upload-offset')}")

a = wait_asset("creator", t["id"], timeout=300)
step("FFmpeg ABR HLS from tus upload → READY", a["asset"]["state"] == "READY", f"renditions {[r['name'] for r in a['renditions']]}")
ups = call("GET", "/api/v1/uploads", "creator").json()
tus_sessions = [u for u in ups if u["titleId"] == t["id"]]
step("upload session recorded with provider=tus, state UPLOADED", bool(tus_sessions) and tus_sessions[0]["provider"] == "tus" and tus_sessions[0]["state"] == "UPLOADED")

print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
