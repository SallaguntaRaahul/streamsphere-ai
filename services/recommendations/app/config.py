import os

DB_DSN = os.environ.get("DB_DSN", "postgresql://recommendations_svc:recommendations_local_pw@localhost:15432/recommendations")
RABBIT_URL = os.environ.get("RABBIT_URL", "amqp://streamsphere:local-rabbit-pw@localhost:5672/")
ISSUER = os.environ.get("JWT_ISSUER", "http://localhost:8180/realms/streamsphere")
JWKS_URI = os.environ.get("JWKS_URI", "http://localhost:8180/realms/streamsphere/protocol/openid-connect/certs")
AUDIENCE = os.environ.get("JWT_AUDIENCE", "streamsphere-api")
EMBEDDER = os.environ.get("EMBEDDER", "auto")  # auto | fastembed | hashing
MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "BAAI/bge-small-en-v1.5")
MODEL_CACHE = os.environ.get("MODEL_CACHE", "/models")
USER_HASH_SALT = os.environ.get("USER_HASH_SALT", "local-dev-salt-change-me")
SERVICE = "recommendations"
DIM = 384
