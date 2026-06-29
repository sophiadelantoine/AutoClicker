#!/usr/bin/env python3
"""ACMRI lint — verify an Actionable, Checkable Markdown Roadmap Implementation plan
is structurally complete and machine-checkable.

Checks (per the ACMRI authoring contract):
  - Frontmatter carries the required header fields (incl. Jira/Confluence, even if placeholder).
  - Required top-level sections exist (Goal, OKRs, Risks, Decision Records, Rollback,
    Evidence Ledger, Final Gate Checklist).
  - Every Phase has entry criteria, exit criteria, validation gates, and stop/blocker conditions.
  - Every task has a stable unique id (P<N>-T<NN>), a Slice, a Definition of Done with
    checkable items, at least one Validation command (fenced code), Traceability,
    Dependencies, and a size.
  - Task ids are unique, well-formed, and sit under the matching phase.
  - The Final Gate Checklist references every phase.

Usage: python3 scripts/acmri_lint.py [path-to-acmri.md]
Exit code 0 == clean, 1 == violations found.
"""
import re
import sys

DEFAULT = "documentation/TRAXINTEL_MVP_ACMRI_2026-06-29.md"

REQUIRED_FRONTMATTER = [
    "title", "status", "owner", "created", "updated",
    "repo_source_path", "jira_issue", "confluence_page", "labels", "affected_paths",
]
REQUIRED_SECTIONS = [
    "## Goal", "## OKRs", "## Risks", "## Decision Records",
    "## Rollback", "## Evidence Ledger", "## Final Gate Checklist",
]
PHASE_LABELS = [
    "**Entry criteria:**", "**Exit criteria:**",
    "**Validation gates:**", "**Stop / blocker conditions:**",
]
TASK_LABELS = ["**Slice:**", "**Definition of Done:**", "**Validation:**",
               "**Traceability:**", "**Dependencies:**"]

PHASE_RE = re.compile(r"^##\s+Phase\s+(P\d+)\s+—\s+(.+)$")
# Task ids are P<n>-T<nn> with an optional lowercase split-suffix (e.g. P0-T04b)
TASK_RE = re.compile(r"^####\s+\[[ xX]\]\s+(P\d+-T\d+[a-z]?)\s+—\s+(.+)$")
# Any '#### [ ]'-style heading is meant to be a task; used to catch malformed ones.
TASKISH_RE = re.compile(r"^####\s+\[[ xX]\]")
ID_RE = re.compile(r"^P\d+-T\d+[a-z]?$")


def blocks_by(lines, start_idx, stop_pred):
    """Return the slice of lines from start_idx+1 until stop_pred(line) is True."""
    out = []
    for ln in lines[start_idx + 1:]:
        if stop_pred(ln):
            break
        out.append(ln)
    return out


def has_bullet_after(block, label):
    """True if `label` appears in block and is followed by >=1 bullet before the next bold label."""
    started = False
    for ln in block:
        s = ln.strip()
        if s == label or s.startswith(label):
            started = True
            continue
        if started:
            if s.startswith("- "):
                return True
            if s.startswith("**") and s.endswith(":**"):
                return False
            if s.startswith("#"):
                return False
    return False


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    try:
        text = open(path, encoding="utf-8").read()
    except OSError as e:
        print(f"FAIL: cannot read {path}: {e}")
        return 1

    lines = text.split("\n")
    violations = []

    # --- frontmatter ---
    if not text.startswith("---"):
        violations.append("missing YAML frontmatter block at top of file")
        fm = ""
    else:
        end = text.find("\n---", 3)
        fm = text[3:end] if end != -1 else ""
    for key in REQUIRED_FRONTMATTER:
        if not re.search(rf"^{re.escape(key)}\s*:", fm, re.MULTILINE):
            violations.append(f"frontmatter missing required field: {key}")

    # --- required top-level sections ---
    for sec in REQUIRED_SECTIONS:
        if not any(ln.strip() == sec for ln in lines):
            violations.append(f"missing required section: {sec}")

    # --- phases ---
    phase_ids = []
    phase_heading_idx = {}
    for i, ln in enumerate(lines):
        m = PHASE_RE.match(ln.strip())
        if m:
            pid = m.group(1)
            phase_ids.append(pid)
            phase_heading_idx[pid] = i
    if not phase_ids:
        violations.append("no phases found (expected '## Phase P0 — ...')")

    for pid in phase_ids:
        i = phase_heading_idx[pid]
        block = blocks_by(lines, i, lambda l: l.strip().startswith("## ") or PHASE_RE.match(l.strip()))
        joined = "\n".join(block)
        for label in PHASE_LABELS:
            if label not in joined:
                violations.append(f"phase {pid}: missing '{label}'")
            elif not has_bullet_after(block, label):
                violations.append(f"phase {pid}: '{label}' has no bullet items")

    # --- tasks ---
    seen_ids = {}
    task_count = 0
    for i, ln in enumerate(lines):
        s = ln.strip()
        m = TASK_RE.match(s)
        if not m:
            if TASKISH_RE.match(s):
                violations.append(f"malformed task heading (cannot parse id/title): {s[:80]}")
            continue
        task_count += 1
        tid, ttitle = m.group(1), m.group(2)
        if not ID_RE.match(tid):
            violations.append(f"task id malformed: {tid}")
        if tid in seen_ids:
            violations.append(f"duplicate task id: {tid}")
        seen_ids[tid] = True

        # which phase is this task under?
        owning = None
        for pid in phase_ids:
            if phase_heading_idx[pid] < i:
                owning = pid
        if owning and not tid.startswith(owning + "-"):
            violations.append(f"task {tid} sits under phase {owning} but id prefix mismatches")

        block = blocks_by(lines, i, lambda l: l.strip().startswith("#### ") or l.strip().startswith("## "))
        joined = "\n".join(block)
        for label in TASK_LABELS:
            if label not in joined:
                violations.append(f"task {tid}: missing '{label}'")
        if "**Definition of Done:**" in joined and not has_bullet_after(block, "**Definition of Done:**"):
            violations.append(f"task {tid}: Definition of Done has no checkable items")
        if "```" not in joined:
            violations.append(f"task {tid}: Validation has no fenced command block")
        if "size:" not in ln and "size:" not in joined:
            violations.append(f"task {tid}: missing size (S/M/L)")

    if task_count == 0:
        violations.append("no tasks found (expected '#### [ ] P0-T01 — ...')")

    # --- final gate references every phase ---
    fg_idx = next((i for i, ln in enumerate(lines) if ln.strip() == "## Final Gate Checklist"), None)
    if fg_idx is not None:
        fg = "\n".join(lines[fg_idx:])
        for pid in phase_ids:
            if pid not in fg:
                violations.append(f"final gate checklist does not reference phase {pid}")

    # --- report ---
    print(f"ACMRI lint: {path}")
    print(f"  phases: {len(phase_ids)}  tasks: {task_count}  unique-ids: {len(seen_ids)}")
    if violations:
        print(f"FAIL — {len(violations)} violation(s):")
        for v in violations:
            print(f"  - {v}")
        return 1
    print("PASS — ACMRI is structurally complete and machine-checkable.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
