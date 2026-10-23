# Media fixtures

All files here are **self-generated** with FFmpeg's `lavfi` test sources (`testsrc`, `testsrc2`, `sine`) and are
dedicated to the public domain (**CC0-1.0**). No third-party or copyrighted media is included.

| File | Purpose | How generated |
|---|---|---|
| fixture-720p-20s.mp4 | happy path: 720p, 30 fps, AAC audio → 360p/480p/720p ladder | `testsrc2` 1280x720 + `sine` 440 Hz, libx264/aac |
| fixture-360p-8s-noaudio.mp4 | no-audio + low-res source (ladder must not upscale) | `testsrc` 640x360 |
| corrupted-random.bin | fails container sniffing at upload completion | random bytes |
| corrupted-truncated.mp4 | valid MP4 signature, garbage body → must fail in ffprobe, non-retryable | first 64 bytes of a real MP4 + random |
| fixture.en.vtt | WebVTT captions | hand-written |

Longer load-test fixtures are generated on demand by `scripts/gen-fixture.sh <seconds>` (not committed).
