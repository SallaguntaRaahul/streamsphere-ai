"""Tiny stdlib client for StreamSphere end-to-end tests (no third-party deps)."""
import hashlib, json, os, subprocess, time, urllib.error, urllib.parse, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
GATEWAY = os.environ.get("GATEWAY_URL", "http://localhost:8080")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_no_redirect = urllib.request.build_opener(NoRedirect)
_tokens = {}


def token(user):
    if user not in _tokens:
        _tokens[user] = subprocess.check_output([os.path.join(ROOT, "scripts", "token.sh"), user], text=True).strip()
    return _tokens[user]


class Headers(dict):
    """Case-insensitive view (the gateway lowercases proxied header names)."""
    def __init__(self, h):
        super().__init__({k.lower(): v for k, v in h.items()})

    def __getitem__(self, k):
        return super().__getitem__(k.lower())

    def get(self, k, d=None):
        return super().get(k.lower(), d)


class Resp:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, Headers(headers), body

    def json(self):
        return json.loads(self.body) if self.body else None

    def __repr__(self):
        return f"<{self.status} {self.body[:300]!r}>"


def call(method, path, user=None, body=None, headers=None, follow=True, raw=False):
    url = path if path.startswith("http") else GATEWAY + path
    h = dict(headers or {})
    data = None
    if body is not None:
        if isinstance(body, (bytes, bytearray)):
            data = bytes(body)
        else:
            data = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
    if user:
        h["Authorization"] = "Bearer " + token(user)
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    opener = urllib.request.build_opener() if follow else _no_redirect
    try:
        with opener.open(req, timeout=60) as r:
            b = r.read()
            return Resp(r.status, dict(r.headers), b if raw else b.decode(errors="replace"))
    except urllib.error.HTTPError as e:
        b = e.read()
        return Resp(e.code, dict(e.headers), b if raw else b.decode(errors="replace"))


def expect(resp, *codes):
    assert resp.status in codes, f"expected {codes}, got {resp}"
    return resp


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_creator(user, name):
    r = call("POST", "/api/v1/creators", user, {"displayName": name, "bio": "e2e", "country": "US", "acceptTerms": True})
    assert r.status in (201, 409), r


def new_title(user, title, genres=("drama",), synopsis="An end-to-end test film."):
    t = expect(call("POST", "/api/v1/creator/titles", user, {"title": title, "synopsis": synopsis, "genres": list(genres),
                                                             "releaseYear": 2026, "language": "en", "maturityRating": "PG"}), 201).json()
    expect(call("POST", "/api/v1/creators/me/attestations", user, {"titleId": t["id"], "licenseType": "ORIGINAL", "confirmRights": True}), 201)
    return t


def upload_file(user, title_id, path, content_type="video/mp4", stop_after_parts=None, idem=None):
    size = os.path.getsize(path)
    hdr = {"Idempotency-Key": idem} if idem else None
    s = expect(call("POST", "/api/v1/uploads", user, {"titleId": title_id, "fileName": os.path.basename(path),
                                                      "contentType": content_type, "sizeBytes": size, "sha256": sha256(path)}, headers=hdr), 201).json()
    parts = put_parts(user, s, path, stop_after_parts)
    return s, parts


def put_parts(user, s, path, stop_after_parts=None, skip=()):
    parts = []
    with open(path, "rb") as f:
        for n in range(1, s["partCount"] + 1):
            chunk = f.read(s["partSize"])
            if n in skip:
                continue
            if stop_after_parts is not None and n > stop_after_parts:
                break
            url = expect(call("POST", f"/api/v1/uploads/{s['id']}/parts/{n}/presign", user), 200).json()["url"]
            r = expect(call("PUT", url, body=chunk), 200)
            parts.append({"partNumber": n, "etag": r.headers.get("ETag") or r.headers.get("Etag")})
    return parts


def wait_asset(user, title_id, states=("READY", "FAILED"), timeout=300):
    end = time.time() + timeout
    while time.time() < end:
        assets = expect(call("GET", f"/api/v1/media/titles/{title_id}/assets", user), 200).json()
        if assets and assets[0]["asset"]["state"] in states:
            return assets[0]
        time.sleep(2)
    raise AssertionError(f"asset for {title_id} not in {states} after {timeout}s: {assets}")


def wait_until(fn, timeout=60, interval=1.0, what="condition"):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    raise AssertionError(f"timed out waiting for {what}; last={last}")
