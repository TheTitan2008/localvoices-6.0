"""Generate independent, deterministic EOM fixtures (numpy 2.5.4, sklearn 1.9.1).

Unlike numpy's default unstable quicksort, stable sorting preserves Prim edge
order when mutual-reachability weights are exactly equal. This matches the
explicit Kotlin tie policy; default sklearn differs on two boundary points.
No Android inference uses Python.
"""
from pathlib import Path
import numpy as np
from sklearn.cluster._hdbscan._linkage import mst_from_data_matrix, make_single_linkage
from sklearn.cluster._hdbscan._tree import tree_to_labels
from sklearn.metrics._dist_metrics import DistanceMetric
from sklearn.neighbors import NearestNeighbors

root = Path(__file__).resolve().parents[1]
source = root / "core/src/test/resources/reference-cases.tsv"
cases = {}
for line in source.read_text(encoding="utf-8").splitlines():
    case, label, vector = line.split("\t")
    cases.setdefault(case, []).append(vector)
result = []
for case, vectors in cases.items():
    x = np.array([list(map(float, v.split(","))) for v in vectors], dtype=np.float32)
    x = (x / np.sqrt(np.sum(x.astype(float)**2, axis=1, keepdims=True))).astype(np.float32).astype(float)
    distances, _ = NearestNeighbors(n_neighbors=3, metric="euclidean").fit(x).kneighbors(x)
    mst = mst_from_data_matrix(x, np.ascontiguousarray(distances[:, -1]), DistanceMetric.get_metric("euclidean"))
    hierarchy = make_single_linkage(mst[np.argsort(mst["distance"], kind="stable")])
    labels, _ = tree_to_labels(hierarchy, min_cluster_size=3)
    for vector, label in zip(vectors, labels):
        result.append(f"{case}\t{label}\t{vector}")
output = source.with_name("reference-cases-stable.tsv")
output.write_text("\n".join(result), encoding="utf-8")
print(output)
