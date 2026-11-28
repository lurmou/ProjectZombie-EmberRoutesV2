#!/usr/bin/env python3
"""Distill Project Zomboid console.txt / DebugLog.txt into a high-signal diagnostic summary."""
import argparse
import os
import re
import sys
from collections import Counter, OrderedDict
from pathlib import Path

DEFAULT_CONSOLE = Path(os.environ.get("PZ_CONSOLE_LOG", r"C:\Users\MuMu\Zomboid\console.txt"))
DEFAULT_LOGS_DIR = Path(os.environ.get("PZ_LOGS_DIR", r"C:\Users\MuMu\Zomboid\Logs"))

LOG_PREFIX_RE = re.compile(
    r"^(?:\[\d{2}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\]\s*)?(?:LOG|WARN|ERROR)\s*:\s*\S+\s+f:\d+(?:\s+at\s+[^>]+)?\s*>\s*"
)
ERROR_START_RE = re.compile(
    r"(?:^ERROR:|\bERROR:\s|\bException\b|\bError:|STACK TRACE|Callframe at:|attempted index:|Caused by:)"
)
STACK_CONT_RE = re.compile(
    r"^(?:\s+at\s+|\s*Caused by:|\s*\.\.\.\s+\d+\s+more|\s*function:\s+.*line\s+#|\s*---|\s*\[ZB\]|\s*$)"
)
WARN_RE = re.compile(r"^WARN\s*:|\[\d{2}-\d{2}-\d{2}[^\]]+\]\s*WARN\s*:")
EMBER_RE = re.compile(r"\[EmberRoutes\]|\[EmberUI\]|emberroutes", re.I)
HIGH_FREQ_TAG_RE = re.compile(r"(\[EmberRoutes\]\[[A-Z0-9_]+\]\s+[A-Z0-9_]+)")
NUM_NORM_RE = re.compile(r"-?\d+(?:\.\d+)?")


def strip_prefix(line: str) -> str:
    return LOG_PREFIX_RE.sub("", line, count=1).strip()


def find_last_boot_index(lines: list[str]) -> int:
    for i in range(len(lines) - 1, -1, -1):
        if "[ZB] patching zombie.debug.DebugLog.log" in lines[i]:
            return i
    for i in range(len(lines) - 1, -1, -1):
        if "===== System specs =====" in lines[i]:
            return max(0, i - 6)
    return 0


def latest_debuglog(logs_dir: Path) -> Path:
    cands = sorted(logs_dir.glob("*_DebugLog.txt"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not cands:
        print(f"No *_DebugLog.txt found in {logs_dir}", file=sys.stderr)
        sys.exit(2)
    return cands[0]


def distill_log(path: Path, full: bool = False, tail_count: int = 15) -> str:
    raw_text = path.read_text(encoding="utf-8", errors="replace")
    raw_lines = raw_text.splitlines()
    raw_bytes = len(raw_text.encode("utf-8", errors="replace"))

    start_idx = 0 if full else find_last_boot_index(raw_lines)
    lines = raw_lines[start_idx:]

    error_counts: OrderedDict[str, list] = OrderedDict()
    warn_counts: OrderedDict[str, int] = OrderedDict()
    ember_milestones: list[str] = []
    ember_diag_counts: Counter = Counter()
    ember_recent: list[str] = []

    i = 0
    n = len(lines)
    while i < n:
        raw = lines[i]
        stripped = strip_prefix(raw)

        if ERROR_START_RE.search(raw) and "111ERROR: not adding suspicious zone" not in raw:
            block = [f"L{start_idx + i + 1}: {raw}"]
            j = i + 1
            while j < n and j < i + 30:
                nxt = lines[j]
                if nxt.startswith(("\t", "    ")) or STACK_CONT_RE.match(nxt):
                    if nxt.strip():
                        block.append(f"L{start_idx + j + 1}: {nxt}")
                    j += 1
                    if not nxt.strip():
                        break
                else:
                    break
            sig = NUM_NORM_RE.sub("#", stripped)
            if sig not in error_counts:
                error_counts[sig] = [1, block]
            else:
                error_counts[sig][0] += 1
            i = j
            continue

        if WARN_RE.search(raw):
            warn_counts[stripped] = warn_counts.get(stripped, 0) + 1

        if EMBER_RE.search(raw):
            m = HIGH_FREQ_TAG_RE.search(stripped)
            if m:
                ember_diag_counts[m.group(1)] += 1
            elif len(ember_milestones) < 60:
                ember_milestones.append(f"L{start_idx + i + 1}: {stripped}")
            ember_recent.append(f"L{start_idx + i + 1}: {stripped}")
            if len(ember_recent) > tail_count:
                ember_recent.pop(0)

        i += 1

    nav_zone_noise = sum(1 for l in lines if "111ERROR: not adding suspicious zone" in l)
    out = [
        f"=== Distilled PZ Log: {path} ===",
        f"Raw: {len(raw_lines)} lines ({raw_bytes / 1024:.1f} KB) | Slice: L{start_idx + 1}..L{len(raw_lines)} ({len(lines)} lines)",
        f"Unique Errors: {len(error_counts)} (plus {nav_zone_noise} Nav-zone noise) | Unique Warnings: {len(warn_counts)}",
    ]

    if error_counts:
        out.append("\n--- ERRORS & STACK TRACES (deduped) ---")
        for _sig, (cnt, block) in error_counts.items():
            suffix = f" [x{cnt}]" if cnt > 1 else ""
            out.append(block[0] + suffix)
            out.extend("  " + b for b in block[1:])

    if warn_counts:
        out.append("\n--- WARNINGS (deduped) ---")
        for msg, cnt in list(warn_counts.items())[:25]:
            out.append(f"  [x{cnt}] {msg}")

    if ember_milestones:
        out.append("\n--- EMBER LIFECYCLE / NON-SPAM LOGS ---")
        out.extend("  " + m for m in ember_milestones)

    if ember_diag_counts:
        out.append("\n--- EMBER HIGH-FREQ DIAG SUMMARY ---")
        for tag, cnt in ember_diag_counts.most_common(25):
            out.append(f"  {tag}: {cnt} lines")

    if ember_recent:
        out.append(f"\n--- LAST {len(ember_recent)} EMBER LINES ---")
        out.extend("  " + r for r in ember_recent)

    text = "\n".join(out)
    out_bytes = len(text.encode("utf-8"))
    ratio = (1.0 - out_bytes / max(raw_bytes, 1)) * 100
    text += f"\n\n[Summary] {len(raw_lines)} -> {len(out)} lines ({raw_bytes/1024:.1f} KB -> {out_bytes/1024:.1f} KB, -{ratio:.1f}%)"
    return text


def main() -> None:
    p = argparse.ArgumentParser(description="Distill Project Zomboid console.txt or DebugLog.txt")
    p.add_argument("log_file", nargs="?", help="Path to log file (default: console.txt)")
    p.add_argument("--debuglog", action="store_true", help="Pick newest *_DebugLog.txt in Zomboid/Logs")
    p.add_argument("--all", action="store_true", help="Include entire file instead of last boot slice")
    p.add_argument("--tail", type=int, default=15, help="Number of trailing Ember lines to keep")
    args = p.parse_args()

    target = latest_debuglog(DEFAULT_LOGS_DIR) if args.debuglog else (Path(args.log_file) if args.log_file else DEFAULT_CONSOLE)
    if not target.exists():
        print(f"Log file not found: {target}", file=sys.stderr)
        sys.exit(2)
    print(distill_log(target, full=args.all, tail_count=args.tail))


if __name__ == "__main__":
    main()
