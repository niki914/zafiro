#!/usr/bin/env python3
"""Collect compact CI results into a directory that gets pushed to the `ci-logs` branch.

Why: the maintainer's coding agent cannot read GitHub Actions logs through its proxy, but it can `git fetch`.
Output (under the directory given as argv[1]): summary.md, per-step log tails, and failing-test excerpts.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
logs = os.environ.get("CI_LOG_DIR", "ci-work")
TAIL = 160


def tail(path, n=TAIL):
    if not os.path.exists(path):
        return "(missing)"
    with open(path, encoding="utf-8", errors="replace") as f:
        return "".join(f.readlines()[-n:])


def error_lines(path, limit=80):
    if not os.path.exists(path):
        return []
    pat = re.compile(r"(^e: |error:|FAILED|What went wrong|Execution failed|Could not |Unresolved reference|\*\* )")
    with open(path, encoding="utf-8", errors="replace") as f:
        return [l.rstrip() for l in f if pat.search(l)][:limit]


failures, total, skipped = [], 0, 0
for xml in glob.glob("**/build/test-results/testDebugUnitTest/*.xml", recursive=True):
    try:
        root = ET.parse(xml).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        total += 1
        if case.find("skipped") is not None:
            skipped += 1
        bad = case.find("failure") if case.find("failure") is not None else case.find("error")
        if bad is not None:
            failures.append((case.get("classname"), case.get("name"), (bad.get("message") or "")[:300],
                             (bad.text or "")[:1200]))

lines = ["# CI result", ""]
for key in ("RUN_URL", "GITHUB_SHA", "GITHUB_REF_NAME"):
    lines.append("- %s: %s" % (key, os.environ.get(key, "")))
lines.append("")
lines.append("| step | outcome |")
lines.append("|---|---|")
for name in ("python_check", "assemble", "unit_tests", "lint"):
    lines.append("| %s | %s |" % (name, os.environ.get("OUTCOME_" + name.upper(), "n/a")))
lines += ["", "Unit tests: %d run, %d failed, %d skipped" % (total, len(failures), skipped), ""]
if failures:
    lines.append("## Failing tests")
    for cls, nm, msg, _ in failures[:60]:
        lines.append("- `%s.%s` — %s" % (cls, nm, msg.replace("\n", " ")))
lines.append("")
for name in ("assemble", "unit_tests", "lint"):
    errs = error_lines(os.path.join(logs, name + ".log"))
    if errs:
        lines.append("## %s: key error lines" % name)
        lines.append("```")
        lines += errs
        lines.append("```")
with open(os.path.join(out, "summary.md"), "w") as f:
    f.write("\n".join(lines) + "\n")
for name in ("python_check", "assemble", "unit_tests", "lint"):
    with open(os.path.join(out, name + ".tail.log"), "w") as f:
        f.write(tail(os.path.join(logs, name + ".log")))
with open(os.path.join(out, "failures.txt"), "w") as f:
    for cls, nm, msg, trace in failures:
        f.write("=== %s.%s\n%s\n%s\n\n" % (cls, nm, msg, trace))
print("\n".join(lines[:40]))
