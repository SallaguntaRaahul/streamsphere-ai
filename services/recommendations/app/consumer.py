"""RabbitMQ consumer with the same topology/semantics as the Java services: inbox dedup in the same DB
transaction, retry queue with exponential backoff + jitter, DLQ after 5 attempts."""
import asyncio, json, logging, random
import aio_pika
from . import config, db, embedder

log = logging.getLogger(__name__)
EXCHANGE, DLX = "streamsphere.events", "streamsphere.dlx"
Q, RETRY, DLQ = f"{config.SERVICE}.events", f"{config.SERVICE}.retry", f"{config.SERVICE}.dlq"
KEYS = ["ContentPublished.v1", "ContentRemoved.v1", "PlaybackStarted.v1", "PlaybackProgressed.v1", "PlaybackCompleted.v1"]
MAX_ATTEMPTS = 5


def handle(env: dict):
    p, t = env["payload"], env["eventType"]
    with db.pool.connection() as c, c.transaction():
        cur = c.execute("insert into inbox_deduplication(consumer, event_id) values (%s, %s) on conflict do nothing",
                        (config.SERVICE, env["eventId"]))
        if cur.rowcount == 0:
            return "duplicate"
        if t == "ContentPublished":
            text = f"{p['title']}. {', '.join(p.get('genres') or [])}. {p.get('synopsis') or ''}"
            e = embedder.get()
            vec = e.embed([text])[0]
            c.execute("""
                insert into embeddings(title_id, title, genres, creator_id, embedding, model, published_at, removed, updated_at)
                values (%s, %s, %s, %s, %s, %s, %s, false, now())
                on conflict (title_id) do update set title = excluded.title, genres = excluded.genres, embedding = excluded.embedding,
                  model = excluded.model, published_at = excluded.published_at, removed = false, updated_at = now()""",
                      (p["titleId"], p["title"], p.get("genres") or [], p["creatorId"], vec, e.name, p.get("publishedAt")))
        elif t == "ContentRemoved":
            c.execute("update embeddings set removed = true where title_id = %s", (p["titleId"],))
        elif t in ("PlaybackStarted", "PlaybackProgressed", "PlaybackCompleted"):
            kind = {"PlaybackStarted": "STARTED", "PlaybackProgressed": "PROGRESS", "PlaybackCompleted": "COMPLETED"}[t]
            from .recommender import WEIGHTS
            c.execute("""
                insert into interactions(user_id, title_id, kind, weight, at) values (%s, %s, %s, %s, now())
                on conflict (user_id, title_id, kind) do update set at = now()""", (p["userId"], p["titleId"], kind, WEIGHTS[kind]))
        return "processed"


async def run():
    while True:
        try:
            conn = await aio_pika.connect_robust(config.RABBIT_URL)
            async with conn:
                ch = await conn.channel(publisher_confirms=True)
                await ch.set_qos(prefetch_count=10)
                ex = await ch.declare_exchange(EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True)
                dlx = await ch.declare_exchange(DLX, aio_pika.ExchangeType.DIRECT, durable=True)
                q = await ch.declare_queue(Q, durable=True, arguments={"x-dead-letter-exchange": DLX, "x-dead-letter-routing-key": DLQ})
                await ch.declare_queue(RETRY, durable=True, arguments={"x-dead-letter-exchange": "", "x-dead-letter-routing-key": Q})
                dlq = await ch.declare_queue(DLQ, durable=True)
                await dlq.bind(dlx, DLQ)
                for k in KEYS:
                    await q.bind(ex, k)
                log.info("Consuming %s", Q)
                async with q.iterator() as it:
                    async for msg in it:
                        await on_message(ch, msg)
        except Exception as e:  # reconnect loop
            log.warning("Consumer error (%s); reconnecting in 5 s", e)
            await asyncio.sleep(5)


async def on_message(ch, msg):
    try:
        env = json.loads(msg.body)
    except ValueError:
        log.error("Poison message; dead-lettering")
        await msg.reject(requeue=False)
        return
    try:
        await asyncio.to_thread(handle, env)
        await msg.ack()
    except Exception as e:
        attempt = int((msg.headers or {}).get("x-attempt", 0)) + 1
        if attempt >= MAX_ATTEMPTS:
            log.error("Event %s failed %d times; dead-lettering: %s", env.get("eventId"), attempt, e)
            await msg.reject(requeue=False)
            return
        cap = min(60_000, 1000 * 2 ** attempt)
        delay = int(random.uniform(cap / 2, cap))
        log.warning("Event %s failed (attempt %d), retry in %d ms: %s", env.get("eventId"), attempt, delay, e)
        await ch.default_exchange.publish(
            aio_pika.Message(msg.body, headers={**(msg.headers or {}), "x-attempt": attempt}, expiration=delay / 1000,
                             content_type="application/json", delivery_mode=aio_pika.DeliveryMode.PERSISTENT), routing_key=RETRY)
        await msg.ack()
