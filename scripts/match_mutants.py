#!/usr/bin/env python3
"""
Match PIT mutants across the five test suites of ONE project and classify them.

Input : a folder containing mutations_Claude.xml, mutations_GPT.xml,
        mutations_Gemini.xml, mutations_DeepSeek.xml, mutations_Human.xml
        (raw PIT XML reports).
Output: per-suite mutation score and the mutant categories used in the paper.

Mutant identity (PIT's own identifiers, unique inside a report):
    mutatedClass + mutatedMethod + methodDescription + lineNumber
    + mutator + index + block
(Class+Method+Line+Mutator alone is NOT unique: several mutants share it.)

Mutators outside PIT's default group (RemoveConditional*, Switch) are ignored unless --all-mutators.
A mutant counts as "detected" if status is KILLED or TIMED_OUT
(PIT's `detected='true'`). SURVIVED and NO_COVERAGE count as not detected.

Categories (LLMs = Claude, GPT, Gemini, DeepSeek):
    all_killed   : Human and all 4 LLMs detect it
    llm_only     : Human misses it, all 4 LLMs detect it   (strict definition)
    human_only   : Human detects it, no LLM does
    neither      : nobody detects it
    mixed        : everything else
Also reported: llm_only_any = Human misses, >=1 LLM detects.

If the five suites do not share an identical mutant set, the script stops
(unmatched mutants are never silently dropped).
"""
import sys, glob, os
import xml.etree.ElementTree as ET
import pandas as pd

# Mutators outside PIT's default group. Only one project (vaulttec) was run with them; to give all
# projects the same mutator set they are ignored by default (pass --all-mutators to keep them).
NON_DEFAULT = ("RemoveConditionalMutator", "SwitchMutator")
LLMS = ["Claude", "GPT", "Gemini", "DeepSeek"]
SUITES = LLMS + ["Human"]
KEY = ["Class", "Method", "Desc", "Line", "Mutator", "Index", "Block"]


def load(path, suite, exclude=NON_DEFAULT):
    rows = []
    for e in ET.parse(path).getroot():
        if exclude and e.findtext("mutator").split(".")[-1].startswith(tuple(exclude)):
            continue
        rows.append(dict(
            Suite=suite,
            Class=e.findtext("mutatedClass"),
            Method=e.findtext("mutatedMethod"),
            Desc=e.findtext("methodDescription"),
            Line=e.findtext("lineNumber"),
            Mutator=e.findtext("mutator").split(".")[-1],
            Index=e.find("indexes/index").text,
            Block=e.find("blocks/block").text,
            Status=e.get("status"),
        ))
    return pd.DataFrame(rows)


def analyse(folder, exclude=NON_DEFAULT):
    D = pd.concat([load(os.path.join(folder, f"mutations_{s}.xml"), s, exclude) for s in SUITES])
    D["det"] = D.Status.isin(["KILLED", "TIMED_OUT"])
    if D.duplicated(["Suite"] + KEY).any():
        sys.exit("duplicate mutant keys inside a report - cannot match safely")
    sets = {s: set(map(tuple, g[KEY].values)) for s, g in D.groupby("Suite")}
    for s in LLMS:
        if sets[s] != sets["Human"]:
            sys.exit(f"mutant set of {s} differs from Human "
                     f"(extra={len(sets[s]-sets['Human'])}, missing={len(sets['Human']-sets[s])})")
    scores = D.groupby("Suite").det.agg(killed="sum", total="count")
    scores["score"] = (scores.killed / scores.total).round(4)
    P = D.pivot_table(index=KEY, columns="Suite", values="det", aggfunc="first").astype(bool)
    L, H = P[LLMS], P["Human"]
    allL, anyL = L.all(axis=1), L.any(axis=1)
    cat = dict(total=len(P), all_killed=int((allL & H).sum()),
               llm_only=int((allL & ~H).sum()), human_only=int((H & ~anyL).sum()),
               neither=int((~anyL & ~H).sum()), llm_only_any=int((anyL & ~H).sum()))
    cat["mixed"] = cat["total"] - cat["all_killed"] - cat["llm_only"] - cat["human_only"] - cat["neither"]
    return scores, cat


if __name__ == "__main__":
    out = []
    keep_all = "--all-mutators" in sys.argv
    for folder in [a for a in sys.argv[1:] if not a.startswith("--")]:
        scores, cat = analyse(folder, exclude=() if keep_all else NON_DEFAULT)
        print(f"\n== {os.path.basename(folder.rstrip('/'))} ==")
        print(scores.loc[SUITES].to_string())
        print(cat)
        out.append(dict(project=os.path.basename(folder.rstrip('/')), **cat))
    if out:
        df = pd.DataFrame(out); df.to_csv("mutant_categories.csv", index=False)
        print("\nTOTAL:", df.drop(columns="project").sum().to_dict())
