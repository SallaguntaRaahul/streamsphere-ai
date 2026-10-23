"""Publishes a CC0 demo catalog through the REAL pipeline (upload → FFmpeg → review → publish).
Each title gets a distinct self-generated clip (FFmpeg lavfi sources) so posters differ. No third-party media."""
import os, subprocess, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tests", "e2e"))
from ss_api import *

OUT = os.path.join(ROOT, "tmp", "seed")
os.makedirs(OUT, exist_ok=True)
CATALOG = [
    ("Tidal Lanterns", ["drama", "romance"], "Two ferry pilots trade letters across a harbor that floods with light every spring.", "mandelbrot=size=1280x720:rate=24", "creator"),
    ("Circuit Garden", ["sci-fi"], "A botanist grows a forest of living circuits that remembers every visitor.", "life=size=320x180:mold=10:rate=24:ratio=0.1:death_color=#0b1d3a:life_color=#4de1c1,scale=1280:720:flags=neighbor", "creator"),
    ("The Last Cartographer", ["documentary", "history"], "An archivist restores hand-drawn maps of coastlines that no longer exist.", "gradients=size=1280x720:rate=24:c0=0x1d2b53:c1=0xff9e5e:speed=0.02", "creator2"),
    ("Static Bloom", ["experimental"], "An abstract study of noise becoming pattern, scored with a single sustained tone.", "cellauto=size=320x180:rate=24:rule=110,scale=1280:720:flags=neighbor,format=yuv420p", "creator2"),
    ("Midnight Signal", ["thriller", "sci-fi"], "A radio operator receives coordinates from a satellite decommissioned decades ago.", "testsrc2=size=1280x720:rate=24,hue=h=200:s=0.4", "creator"),
    ("Paper Comets", ["animation", "family"], "Children fold paper comets that drift into the night sky and never come down.", "gradients=size=1280x720:rate=24:c0=0x2e1065:c1=0xf472b6:c2=0x60a5fa:nb_colors=3:speed=0.03", "creator2"),
    ("Salt & Iron", ["drama", "history"], "A shipwright's daughter rebuilds her father's last vessel against the town's wishes.", "smptehdbars=size=1280x720:rate=24,hue=s=0.2", "creator"),
    ("Orbit Kitchen", ["comedy", "sci-fi"], "Three astronauts attempt to cook a birthday dinner in zero gravity.", "rgbtestsrc=size=1280x720:rate=24,hue=h=40", "creator2"),
    ("Echoes of the Valley", ["documentary", "nature"], "A year of sound recordings from a valley as glaciers recede.", "zoneplate=size=1280x720:rate=24:kt2=2:kx2=64:ky2=64", "creator"),
    ("Neon Nocturne", ["music", "experimental"], "A synthesizer duet performed across two cities, visualized as light.", "mandelbrot=size=1280x720:rate=24:start_scale=1.5:end_scale=0.3:inner=period", "creator2"),
]


def make_clip(i, title, src):
    path = os.path.join(OUT, f"seed-{i:02d}.mp4")
    if os.path.exists(path):
        return path
    text = title.replace("'", "").replace(":", "")
    cmd = ["docker", "run", "--rm", "--entrypoint", "ffmpeg", "-u", "10001", "--tmpfs", "/tmp", "-v", f"{OUT}:/out",
           "streamsphere/ffmpeg-worker:local", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", src + ",format=yuv420p",
           "-f", "lavfi", "-i", f"sine=frequency={220 + 40 * i}:sample_rate=48000", "-t", "16",
           "-vf", f"drawtext=text='{text}':fontsize=64:fontcolor=white:x=(w-tw)/2:y=h-160:shadowcolor=black@0.6:shadowx=3:shadowy=3",
           "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-shortest",
           f"/out/seed-{i:02d}.mp4"]
    subprocess.run(cmd, check=True)
    return path


def main():
    ensure_creator("creator", "Cyrus Films")
    ensure_creator("creator2", "Chloe Studio")
    existing = {t["title"] for t in call("GET", "/api/v1/titles?limit=60").json()["items"]}
    vtt = open(os.path.join(ROOT, "tests", "fixtures", "media", "fixture.en.vtt")).read()
    pending = []
    for i, (title, genres, syn, src, owner) in enumerate(CATALOG):
        if title in existing:
            print("exists:", title)
            continue
        clip = make_clip(i, title, src)
        t = new_title(owner, title, genres, syn)
        s, parts = upload_file(owner, t["id"], clip)
        expect(call("POST", f"/api/v1/uploads/{s['id']}/complete", owner, {"parts": parts}), 200)
        pending.append((t, owner))
        print("uploaded:", title)
    for t, owner in pending:
        a = wait_asset(owner, t["id"])
        if a["asset"]["state"] != "READY":
            print("FAILED:", t["title"], a["asset"]["error"]); continue
        call("POST", f"/api/v1/media/assets/{a['asset']['id']}/captions", owner, {"language": "en", "label": "English", "isDefault": False, "webvtt": vtt})
        wait_until(lambda: call("GET", f"/api/v1/creator/titles/{t['id']}", owner).json()["mediaState"] == "READY", 30)
        expect(call("POST", f"/api/v1/creator/titles/{t['id']}/submit", owner), 200)
        case = wait_until(lambda: next((c for c in call("GET", "/api/v1/moderation/queue", "moderator").json() if c["titleId"] == t["id"]), None), 30)
        expect(call("POST", f"/api/v1/moderation/cases/{case['id']}/decision", "moderator", {"decision": "APPROVE"}), 200)
        wait_until(lambda: call("GET", f"/api/v1/creator/titles/{t['id']}", owner).json()["editorialState"] == "APPROVED", 30)
        expect(call("POST", f"/api/v1/creator/titles/{t['id']}/publish", owner), 200)
        print("published:", t["title"])


if __name__ == "__main__":
    main()
