#!/usr/bin/env python3
"""
Rebuild every result table from the raw PIT reports.

Layout expected:   <root>/<project>/mutations_{Claude,GPT,Gemini,DeepSeek,Human}.xml
Usage:             python3 build_results.py <root>
Writes (in the current folder):
    mutation_scores_90.csv   one row per project x suite
    mutant_categories.csv    per-project mutant categories (+ a TOTAL row)
Uses match_mutants.analyse(): exact PIT identity, KILLED+TIMED_OUT = detected.
"""
import os, sys
import xml.etree.ElementTree as ET
import pandas as pd
import match_mutants as mm

def status_counts(path):
    c = {"KILLED": 0, "TIMED_OUT": 0, "SURVIVED": 0, "NO_COVERAGE": 0}
    for e in ET.parse(path).getroot():
        if e.findtext("mutator").split(".")[-1].startswith(mm.NON_DEFAULT):
            continue
        c[e.get("status")] = c.get(e.get("status"), 0) + 1
    return c

root = sys.argv[1]
score_rows, cat_rows = [], []
for proj in sorted(os.listdir(root)):
    folder = os.path.join(root, proj)
    if not os.path.isdir(folder):
        continue
    scores, cat = mm.analyse(folder)                      # stops if mutant sets differ
    for s in mm.SUITES:
        c = status_counts(os.path.join(folder, f"mutations_{s}.xml"))
        total = sum(c.values()); killed = c["KILLED"] + c["TIMED_OUT"]
        assert killed == int(scores.loc[s, "killed"]) and total == int(scores.loc[s, "total"])
        score_rows.append(dict(project=proj, suite=s, total_mutants=total, killed=killed,
                               timed_out=c["TIMED_OUT"], survived=c["SURVIVED"],
                               no_coverage=c["NO_COVERAGE"], score=round(killed / total, 4)))
    cat_rows.append(dict(project=proj, **cat))
S = pd.DataFrame(score_rows)
S["suite"] = pd.Categorical(S.suite, mm.SUITES, ordered=True)
S.sort_values(["project", "suite"]).to_csv("mutation_scores_90.csv", index=False)
C = pd.DataFrame(cat_rows)
tot = C.drop(columns="project").sum(); tot["project"] = "TOTAL"
C = pd.concat([C, pd.DataFrame([tot])], ignore_index=True)
C.to_csv("mutant_categories.csv", index=False)
print(C.to_string(index=False))
