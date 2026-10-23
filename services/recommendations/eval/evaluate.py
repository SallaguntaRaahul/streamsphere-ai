"""Offline recommendation evaluation on the LIVE published catalog with SYNTHETIC users.

Honesty note: there is no real user data. Each synthetic user prefers 1-2 genres and has watched 3+
titles from them; we hold out their most recent title (leave-one-out) and ask each strategy to rank the
rest. Metrics: Recall@K, NDCG@K, MRR, intra-list diversity (1 - mean pairwise cosine), latency.
Results describe this toy catalog only and are NOT predictive of real-world quality.

Run: docker compose exec -T recommendations python -m eval.evaluate
"""
import json, math, random, statistics, time
from datetime import datetime, timezone
import numpy as np
from app import db
from app.recommender import _np, mmr

K = 5
SEED = 42


def load(conn):
    rows = conn.execute("select title_id, title, genres, embedding from embeddings where not removed").fetchall()
    return [{"id": str(r[0]), "title": r[1], "genres": r[2] or [], "emb": _np(r[3])} for r in rows]


def content_rank(history, catalog):
    prof = np.mean([t["emb"] for t in history], axis=0)
    prof /= np.linalg.norm(prof) or 1
    seen = {t["id"] for t in history}
    gaff = {}
    for t in history:
        for g in t["genres"]:
            gaff[g] = gaff.get(g, 0) + 1
    scored = [{"titleId": t["id"], "score": float(prof @ t["emb"]) + 0.15 * sum(gaff.get(g, 0) for g in t["genres"]) / len(history), "emb": t["emb"]}
              for t in catalog if t["id"] not in seen]
    return [s["titleId"] for s in mmr(scored, K, lam=0.8)]


def popularity_rank(history, catalog, rng):
    seen = {t["id"] for t in history}
    pool = [t["id"] for t in catalog if t["id"] not in seen]
    rng.shuffle(pool)  # no real popularity signal in a fresh catalog: random baseline
    return pool[:K]


def metrics(ranked, target):
    hit = target in ranked
    rank = ranked.index(target) + 1 if hit else None
    return {"recall": 1.0 if hit else 0.0, "ndcg": 1 / math.log2(rank + 1) if hit else 0.0, "mrr": 1 / rank if hit else 0.0}


def diversity(ids, by_id):
    embs = [by_id[i]["emb"] for i in ids]
    if len(embs) < 2:
        return 0.0
    sims = [float(embs[i] @ embs[j]) for i in range(len(embs)) for j in range(i + 1, len(embs))]
    return 1 - statistics.mean(sims)


def main():
    db.init()
    rng = random.Random(SEED)
    with db.pool.connection() as c:
        catalog = load(c)
    by_id = {t["id"]: t for t in catalog}
    genres = sorted({g for t in catalog for g in t["genres"]})
    users = []
    for _ in range(200):
        prefs = set(rng.sample(genres, k=min(2, len(genres))))
        liked = [t for t in catalog if prefs & set(t["genres"])]
        if len(liked) >= 3:
            rng.shuffle(liked)
            users.append(liked[:min(4, len(liked))])
    out = {}
    for name, fn in (("content-embedding+mmr", lambda h: content_rank(h, catalog)), ("random-baseline", lambda h: popularity_rank(h, catalog, rng))):
        ms, divs, lat = [], [], []
        for u in users:
            history, target = u[:-1], u[-1]["id"]
            t0 = time.perf_counter()
            ranked = fn(history)
            lat.append((time.perf_counter() - t0) * 1000)
            ms.append(metrics(ranked, target))
            divs.append(diversity(ranked, by_id))
        out[name] = {f"Recall@{K}": round(statistics.mean(m["recall"] for m in ms), 3), f"NDCG@{K}": round(statistics.mean(m["ndcg"] for m in ms), 3),
                     "MRR": round(statistics.mean(m["mrr"] for m in ms), 3), "diversity": round(statistics.mean(divs), 3),
                     "p50_ms": round(statistics.median(lat), 2), "p95_ms": round(sorted(lat)[int(len(lat) * 0.95) - 1], 2)}
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "catalogSize": len(catalog), "syntheticUsers": len(users), "k": K,
              "seed": SEED, "results": out,
              "caveat": "Synthetic genre-preference users on a 10-title demo catalog; numbers are not indicative of production quality."}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
