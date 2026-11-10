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
xmls = glob.glob("**/build/test-results/testDebugUnitTest/*.xml", recursive=True) + \
    glob.glob("**/build/test-results/test/*.xml", recursive=True)
for xml in xmls:
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

known, min_tests = set(), 0
kpath = os.path.join(os.path.dirname(os.path.abspath(__file__)), "known_test_failures.txt")
if os.path.exists(kpath):
    for l in open(kpath, encoding="utf-8"):
        l = l.strip()
        if l.startswith("MIN_TESTS="):
            min_tests = int(l.split("=", 1)[1])
        elif l and not l.startswith("#"):
            known.add(l)
new_failures = [f for f in failures if "%s.%s" % (f[0], f[1]) not in known]
known_hit = [f for f in failures if "%s.%s" % (f[0], f[1]) in known]
env = os.environ.get
reasons = []
if env("OUTCOME_ASSEMBLE") != "success":
    reasons.append("assembleDebug failed")
if env("OUTCOME_PYTHON_CHECK") not in ("success", "skipped"):
    reasons.append("fork policy check failed")
if new_failures:
    reasons.append("%d NEW test failure(s)" % len(new_failures))
if total < min_tests:
    reasons.append("only %d tests ran (minimum %d) - a module probably failed to compile" % (total, min_tests))
gate = "PASS" if not reasons else "FAIL: " + "; ".join(reasons)
with open(os.path.join(out, "gate.txt"), "w") as f:
    f.write(gate + "\n")

lines = ["# CI result", "", "**Gate: %s**" % gate, ""]
for key in ("RUN_URL", "GITHUB_SHA", "GITHUB_REF_NAME"):
    lines.append("- %s: %s" % (key, os.environ.get(key, "")))
lines.append("")
lines.append("| step | outcome |")
lines.append("|---|---|")
for name in ("python_check", "assemble", "unit_tests", "lint"):
    lines.append("| %s | %s |" % (name, os.environ.get("OUTCOME_" + name.upper(), "n/a")))
lines += ["", "Unit tests: %d run, %d failed (%d known upstream, %d NEW), %d skipped" %
          (total, len(failures), len(known_hit), len(new_failures), skipped), ""]
if new_failures:
    lines.append("## NEW failing tests")
    for cls, nm, msg, _ in new_failures[:60]:
        lines.append("- `%s.%s` — %s" % (cls, nm, msg.replace("\n", " ")))
if known_hit:
    lines.append("## Known upstream failures (also fail on pristine upstream)")
    for cls, nm, msg, _ in known_hit:
        lines.append("- `%s.%s`" % (cls, nm))
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
for name in ("assemble", "unit_tests", "diagnose"):
    path = os.path.join(logs, name + ".log")
    if os.path.exists(path):
        with open(path, encoding="utf-8", errors="replace") as src:
            lines_all = src.readlines()
        with open(os.path.join(out, name + ".head.log"), "w") as dst:
            dst.writelines(lines_all[:1500])
with open(os.path.join(out, "failures.txt"), "w") as f:
    for cls, nm, msg, trace in failures:
        f.write("=== %s.%s\n%s\n%s\n\n" % (cls, nm, msg, trace))
print("\n".join(lines[:40]))


def annotate(title, text, limit=14000):
    """Emit a GitHub error annotation (readable through the check-runs API even when logs are not)."""
    text = text[-limit:]
    esc = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::error title=%s::%s" % (title, esc))


annotate("ci-summary", "\n".join(lines))
for name in ("python_check", "assemble", "unit_tests", "lint"):
    t = tail(os.path.join(logs, name + ".log"), 70)
    if t and t != "(missing)":
        annotate("ci-" + name + "-tail", t)
