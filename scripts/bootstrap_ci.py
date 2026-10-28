#!/usr/bin/env python3
"""
95% percentile-bootstrap CIs over PROJECTS (n = 18), 10,000 resamples, seed 42.
Input : mutation_scores_90.csv   (project, suite, total_mutants, killed, score, ...)
Output: bootstrap_ci.csv
Statistics: (a) mean mutation score per suite, (b) mean paired difference
(suite - Human) per LLM, resampling whole projects so pairs stay together.
"""
import numpy as np, pandas as pd

B, SEED = 10_000, 42
df = pd.read_csv("mutation_scores_90.csv")
W = df.pivot(index="project", columns="suite", values="score")[["Claude", "GPT", "Gemini", "DeepSeek", "Human"]]
assert W.shape == (18, 5) and not W.isna().any().any()

rng = np.random.default_rng(SEED)
idx = rng.integers(0, len(W), size=(B, len(W)))          # same resample for every statistic
rows = []
for s in W.columns:
    m = W[s].values[idx].mean(axis=1)
    rows.append(dict(statistic=f"mean score: {s}", estimate=W[s].mean(),
                     ci_low=np.percentile(m, 2.5), ci_high=np.percentile(m, 97.5)))
for s in ["Claude", "GPT", "Gemini", "DeepSeek"]:
    d = (W[s] - W["Human"]).values
    m = d[idx].mean(axis=1)
    rows.append(dict(statistic=f"mean difference: {s} - Human", estimate=d.mean(),
                     ci_low=np.percentile(m, 2.5), ci_high=np.percentile(m, 97.5)))
# Sensitivity: drop the projects where the developer suite scored 0 (the raw reports show 0 kills)
zero = W.index[W["Human"] == 0]
W2 = W.drop(index=zero)
idx2 = np.random.default_rng(SEED).integers(0, len(W2), size=(B, len(W2)))
for s in ["Claude", "GPT", "Gemini", "DeepSeek"]:
    d = (W2[s] - W2["Human"]).values
    m = d[idx2].mean(axis=1)
    rows.append(dict(statistic=f"[excl. {len(zero)} projects with Human=0] mean difference: {s} - Human",
                     estimate=d.mean(), ci_low=np.percentile(m, 2.5), ci_high=np.percentile(m, 97.5)))
out = pd.DataFrame(rows).round(4)
out.to_csv("bootstrap_ci.csv", index=False)
print(out.to_string(index=False))
