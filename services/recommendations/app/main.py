import asyncio, hashlib, logging, time, uuid
from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.responses import PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, Histogram, generate_latest
from pydantic import BaseModel, Field
from . import auth, config, consumer, db, embedder, recommender

logging.basicConfig(level=logging.INFO, format='{"ts":"%(asctime)s","level":"%(levelname)s","logger":"%(name)s","service":"recommendations","message":"%(message)s"}')
LAT = Histogram("streamsphere_recommendation_latency_seconds", "recommendation latency", ["strategy"])
EMB_LAT = Histogram("streamsphere_embedding_latency_seconds", "embedding latency")


@asynccontextmanager
async def lifespan(app):
    db.init()
    embedder.get()  # load model at startup, not on first request
    task = asyncio.create_task(consumer.run())
    yield
    task.cancel()


app = FastAPI(title="StreamSphere Recommendations", version="0.1.0", lifespan=lifespan,
              docs_url="/swagger-ui", openapi_url="/v3/api-docs")


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=32)


@app.post("/internal/embed")
def embed(req: EmbedRequest, _=Depends(auth.require_service)):
    if any(len(t) > 4000 for t in req.texts):
        raise HTTPException(400, "text too long")
    t = time.perf_counter()
    e = embedder.get()
    vecs = e.embed(req.texts)
    EMB_LAT.observe(time.perf_counter() - t)
    return {"model": e.name, "dim": config.DIM, "vectors": vecs.round(6).tolist()}


@app.get("/api/v1/recommendations")
def recommendations(limit: int = Query(20, ge=1, le=50), claims: dict | None = Depends(auth.optional_user)):
    t = time.perf_counter()
    with db.pool.connection() as c:
        items, strategy = None, "popularity"
        if claims:
            items = recommender.personalized(c, claims["sub"], limit)
            strategy = "personalized" if items else "popularity"
        if not items:
            items = recommender.popular(c, limit)
        ms = (time.perf_counter() - t) * 1000
        uh = hashlib.sha256(f"{config.USER_HASH_SALT}:{claims['sub']}".encode()).hexdigest() if claims else None
        c.execute("insert into recommendation_runs(id, user_hash, strategy, item_count, latency_ms) values (%s, %s, %s, %s, %s)",
                  (uuid.uuid4(), uh, strategy, len(items), ms))
    LAT.labels(strategy).observe(ms / 1000)
    return {"strategy": strategy, "embeddingModel": embedder.get().name, "items": items, "latencyMs": round(ms, 1)}


@app.get("/api/v1/recommendations/similar/{title_id}")
def similar(title_id: uuid.UUID, limit: int = Query(12, ge=1, le=30)):
    with db.pool.connection() as c:
        return {"strategy": "item-similarity", "items": recommender.similar(c, title_id, limit)}


@app.get("/actuator/health")
@app.get("/actuator/health/readiness")
def health():
    try:
        with db.pool.connection() as c:
            c.execute("select 1")
        return {"status": "UP", "embedder": embedder.get().name}
    except Exception:
        raise HTTPException(503, "DOWN")


@app.get("/actuator/health/liveness")
def liveness():
    return {"status": "UP"}


@app.get("/actuator/prometheus", response_class=PlainTextResponse)
def metrics():
    return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)
