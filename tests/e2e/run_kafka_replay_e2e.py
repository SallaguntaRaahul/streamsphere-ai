"""E2E (local-full): playback events land in a 3-partition Kafka topic; an admin replay from offset 0 with a
fresh consumer group rebuilds analytics' raw-event projection exactly (idempotent by event id)."""
import os, sys, time
sys.path.insert(0, os.path.dirname(__file__))
from ss_api import *

ok = []
def step(name, cond, detail=""):
    ok.append(bool(cond)); print(("PASS  " if cond else "FAIL  ") + name + (f" — {detail}" if detail else ""))

titles = [t["id"] for t in call("GET", "/api/v1/titles?limit=8").json()["items"]]
for tid in titles:  # keyed by titleId → spread over partitions, ordered within a title
    expect(call("POST", "/api/v1/playback/sessions", "viewer", {"titleId": tid}), 201)
time.sleep(6)
first = expect(call("POST", "/api/v1/analytics/admin/stream/replay", "admin"), 200).json()
step("events appended to Kafka across partitions", first["recordsRead"] >= len(titles) and sum(1 for p in first["partitions"].values() if p["endOffset"] > 0) >= 2, f"{first['recordsRead']} records, {first['partitions']}")
before = first["rawEventsAfterReplay"]
again = expect(call("POST", "/api/v1/analytics/admin/stream/replay", "admin"), 200).json()
step("replaying the same log again adds no duplicates", again["rawEventsAfterReplay"] == before, f"{before} → {again['rawEventsAfterReplay']}")
rebuilt = expect(call("POST", "/api/v1/analytics/admin/stream/replay?rebuild=true", "admin"), 200).json()
step("log-covered window deleted and rebuilt from offset 0 by a new consumer group; older history preserved",
     rebuilt["rawEventsAfterReplay"] == before and rebuilt["rowsReplacedInWindow"] >= len(titles) and rebuilt["consumerGroup"] != first["consumerGroup"],
     f"replaced {rebuilt['rowsReplacedInWindow']} rows since {rebuilt['logCoversFrom']}; total {before} → {rebuilt['rawEventsAfterReplay']}")
step("non-admin cannot replay", call("POST", "/api/v1/analytics/admin/stream/replay", "viewer").status == 403)
print(f"\n{sum(ok)}/{len(ok)} passed"); sys.exit(0 if all(ok) else 1)
