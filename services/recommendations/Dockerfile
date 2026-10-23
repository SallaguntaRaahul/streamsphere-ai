# syntax=docker/dockerfile:1.7
FROM python:3.12-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 MODEL_CACHE=/models
WORKDIR /srv
COPY pyproject.toml .
COPY app app
COPY eval eval
COPY migrations migrations
# Dependency install must fail the build if it fails (no silent fallbacks).
RUN pip install --no-cache-dir ".[model]" && python -c "import uvicorn, fastapi, psycopg, aio_pika, jwt, fastembed"
# Model download may be unavailable offline: the service then uses the documented hashing fallback.
RUN mkdir -p /models && (python -c "from fastembed import TextEmbedding; TextEmbedding(model_name='BAAI/bge-small-en-v1.5', cache_dir='/models'); print('model cached')" \
    || echo "WARNING: embedding model not cached at build time; hashing fallback will be used")
RUN useradd -u 10001 -M app && chown -R 10001 /models
USER 10001
EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-server-header"]

FROM base AS runtime
