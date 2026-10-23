import numpy as np
from app import embedder, recommender


def test_hashing_embedder_is_deterministic_and_normalized():
    e = embedder.HashingEmbedder()
    a, b = e.embed(["quiet films about the sea", "quiet films about the sea"])
    assert np.allclose(a, b)
    assert abs(np.linalg.norm(a) - 1.0) < 1e-5
    assert a.shape == (384,)


def test_hashing_embedder_ranks_related_text_closer():
    e = embedder.HashingEmbedder()
    q, near, far = e.embed(["space astronauts cooking", "astronauts cooking dinner in space", "orchard seasons documentary"])
    assert float(q @ near) > float(q @ far)


def test_mmr_prefers_diverse_items_over_near_duplicates():
    v = lambda *x: np.array(x, dtype=np.float32) / np.linalg.norm(x)
    items = [{"titleId": "a", "score": 1.00, "emb": v(1, 0, 0)},
             {"titleId": "a-dup", "score": 0.99, "emb": v(1, 0.01, 0)},
             {"titleId": "b", "score": 0.90, "emb": v(0, 1, 0)}]
    picked = [i["titleId"] for i in recommender.mmr(items, 2, lam=0.6)]
    assert picked == ["a", "b"]


def test_vector_conversion_accepts_both_driver_shapes():
    class FakeVector:
        def to_numpy(self):
            return np.array([1.0, 2.0])
    assert recommender._np(FakeVector()).tolist() == [1.0, 2.0]
    assert recommender._np([3.0, 4.0]).dtype == np.float32
