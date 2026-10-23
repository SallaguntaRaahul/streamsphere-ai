"""Explainable recommendations.

personalized: profile = recency-decayed, interaction-weighted mean of watched-title embeddings →
  pgvector kNN candidates → score = cosine + genre affinity + small popularity prior → MMR re-rank for
  diversity. Each item explains itself ("Because you watched …", "You watch a lot of …").
cold start / anonymous: popularity over 30 days (recency fallback), genre-diversified.
"""
import math, time
from collections import Counter
import numpy as np

def _np(v):
    """pgvector may return a Vector object or a numpy array depending on version."""
    return np.asarray(v.to_numpy() if hasattr(v, "to_numpy") else v, dtype=np.float32)


WEIGHTS = {"STARTED": 1.0, "PROGRESS": 2.0, "COMPLETED": 3.0}
HALF_LIFE_DAYS = 30.0


def _popularity(conn):
    rows = conn.execute("""
        select e.title_id, e.title, e.genres, coalesce(p.plays, 0) as plays, e.published_at
        from embeddings e left join (
            select title_id, count(*) as plays from interactions where kind = 'STARTED' and at > now() - interval '30 days' group by title_id
        ) p on p.title_id = e.title_id
        where not e.removed order by plays desc, e.published_at desc nulls last limit 200""").fetchall()
    return rows


def popular(conn, limit, exclude=()):
    rows = [r for r in _popularity(conn) if str(r[0]) not in exclude]
    out, seen = [], Counter()
    # genre diversity: at most 2 consecutive picks per primary genre until the list is filled
    for pass_ in (0, 1):
        for r in rows:
            if len(out) >= limit:
                break
            if any(o["titleId"] == str(r[0]) for o in out):
                continue
            g = (r[2] or ["other"])[0]
            if pass_ == 0 and seen[g] >= 2:
                continue
            seen[g] += 1
            reason = f"Trending on StreamSphere ({r[3]} plays this month)" if r[3] > 0 else "New on StreamSphere"
            out.append({"titleId": str(r[0]), "title": r[1], "genres": r[2], "score": round(math.log1p(r[3]), 4),
                        "reasons": [reason], "strategy": "popularity"})
    return out


def personalized(conn, user_id, limit):
    hist = conn.execute("""
        select i.title_id, i.kind, extract(epoch from now() - i.at) / 86400.0 as age_days, e.embedding, e.title, e.genres
        from interactions i join embeddings e on e.title_id = i.title_id
        where i.user_id = %s and not e.removed""", (user_id,)).fetchall()
    if not hist:
        return None
    seeds = {}
    for tid, kind, age, emb, title, genres in hist:
        w = WEIGHTS[kind] * 0.5 ** (float(age) / HALF_LIFE_DAYS)
        s = seeds.setdefault(str(tid), {"w": 0.0, "emb": _np(emb), "title": title, "genres": genres or []})
        s["w"] += w
    completed = {str(r[0]) for r in hist if r[1] == "COMPLETED"}
    total = sum(s["w"] for s in seeds.values())
    profile = sum(s["w"] * s["emb"] for s in seeds.values()) / total
    profile /= np.linalg.norm(profile) or 1.0
    genre_aff = Counter()
    for s in seeds.values():
        for g in s["genres"]:
            genre_aff[g] += s["w"]
    gtotal = sum(genre_aff.values()) or 1.0
    pop = {str(r[0]): r[3] for r in _popularity(conn)}
    maxpop = max(pop.values(), default=0)
    cands = conn.execute("""
        select title_id, title, genres, embedding, 1 - (embedding <=> %s) as sim from embeddings
        where not removed order by embedding <=> %s limit 60""", (profile, profile)).fetchall()
    scored = []
    for tid, title, genres, emb, sim in cands:
        tid = str(tid)
        if tid in completed:
            continue
        emb = _np(emb)
        aff = sum(genre_aff[g] for g in (genres or [])) / gtotal
        prior = math.log1p(pop.get(tid, 0)) / math.log1p(maxpop) if maxpop else 0.0
        score = float(sim) + 0.15 * aff + 0.05 * prior
        reasons = []
        others = [(sid, s) for sid, s in seeds.items() if sid != tid]
        if others:
            sid, best = max(others, key=lambda kv: float(np.dot(kv[1]["emb"], emb)))
            reasons.append(f"Because you watched “{best['title']}”")
        top_g = [g for g in (genres or []) if genre_aff[g] / gtotal >= 0.25]
        if top_g:
            reasons.append(f"You watch a lot of {top_g[0]}")
        if tid in seeds:
            reasons.insert(0, "Continue where you left off")
        scored.append({"titleId": tid, "title": title, "genres": genres, "score": round(score, 4), "emb": emb,
                       "reasons": reasons or ["Similar to your viewing"], "strategy": "content-embedding"})
    picked = mmr(scored, limit, lam=0.8)
    for p in picked:
        p.pop("emb", None)
    if len(picked) < limit:  # top up with popular titles
        picked += popular(conn, limit - len(picked), exclude={p["titleId"] for p in picked} | completed)
    return picked


def mmr(items, k, lam=0.8):
    chosen, rest = [], sorted(items, key=lambda x: -x["score"])
    while rest and len(chosen) < k:
        def val(it):
            div = max((float(np.dot(it["emb"], c["emb"])) for c in chosen), default=0.0)
            return lam * it["score"] - (1 - lam) * div
        best = max(rest, key=val)
        chosen.append(best)
        rest.remove(best)
    return chosen


def similar(conn, title_id, limit):
    row = conn.execute("select embedding, title from embeddings where title_id = %s and not removed", (title_id,)).fetchone()
    if not row:
        return []
    rows = conn.execute("""
        select title_id, title, genres, 1 - (embedding <=> %s) as sim from embeddings
        where not removed and title_id <> %s order by embedding <=> %s limit %s""", (row[0], title_id, row[0], limit)).fetchall()
    return [{"titleId": str(r[0]), "title": r[1], "genres": r[2], "score": round(float(r[3]), 4),
             "reasons": [f"Similar themes to “{row[1]}”"], "strategy": "item-similarity"} for r in rows]
