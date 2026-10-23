"""CPU embeddings. fastembed (ONNX) with BAAI/bge-small-en-v1.5 when available; otherwise a deterministic
feature-hashing embedder so the system degrades gracefully (results are flagged with the model name)."""
import hashlib, logging, re
import numpy as np
from . import config

log = logging.getLogger(__name__)
TOKEN = re.compile(r"[a-z0-9]+")


class HashingEmbedder:
    name = "hashing-v1"

    def embed(self, texts):
        out = np.zeros((len(texts), config.DIM), dtype=np.float32)
        for i, t in enumerate(texts):
            toks = TOKEN.findall(t.lower())
            feats = toks + [a + "_" + b for a, b in zip(toks, toks[1:])]
            for f in feats:
                h = int.from_bytes(hashlib.blake2b(f.encode(), digest_size=8).digest(), "little")
                out[i, h % config.DIM] += 1.0 if (h >> 63) & 1 else -1.0
            n = np.linalg.norm(out[i])
            if n > 0:
                out[i] /= n
        return out


class FastEmbedEmbedder:
    def __init__(self):
        from fastembed import TextEmbedding  # optional dependency
        self.model = TextEmbedding(model_name=config.MODEL_NAME, cache_dir=config.MODEL_CACHE)
        self.name = config.MODEL_NAME

    def embed(self, texts):
        vecs = np.array(list(self.model.embed(list(texts))), dtype=np.float32)
        return vecs / np.linalg.norm(vecs, axis=1, keepdims=True)


_instance = None


def get():
    global _instance
    if _instance is None:
        if config.EMBEDDER in ("auto", "fastembed"):
            try:
                _instance = FastEmbedEmbedder()
                log.info("Embedder: %s (fastembed/ONNX, CPU)", _instance.name)
            except Exception as e:  # model or library unavailable
                if config.EMBEDDER == "fastembed":
                    raise
                log.warning("fastembed unavailable (%s); using hashing fallback", e)
        if _instance is None:
            _instance = HashingEmbedder()
    return _instance
