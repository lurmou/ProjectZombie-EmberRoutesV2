#!/usr/bin/env python3
"""Antigravity Stop hook: runs fast i18n/arch gates only when relevant files are dirty."""
import json
import subprocess
import sys
from pathlib import Path


def emit(payload: dict) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main() -> None:
    raw = sys.stdin.read().strip()
    if raw:
        try:
            hook_input = json.loads(raw)
        except Exception:
            emit({"decision": "allow"})
            return
    else:
        hook_input = {}

    if hook_input.get("continuationActive"):
        emit({"decision": "allow"})
        return
    term_reason = hook_input.get("terminationReason", "NO_TOOL_CALL")
    if term_reason != "NO_TOOL_CALL":
        emit({"decision": "allow"})
        return

    repo_root = Path(__file__).resolve().parent.parent
    st = subprocess.run(
        ["git", "-C", str(repo_root), "status", "--porcelain"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if st.returncode != 0:
        emit({"decision": "allow"})
        return

    dirty_paths = [
        line[3:].strip().strip('"').split(" -> ")[-1].replace("\\", "/")
        for line in st.stdout.splitlines()
        if len(line) > 3 and line.strip()
    ]
    need_i18n = any("/translate/" in p.lower() and p.lower().endswith(".json") for p in dirty_paths)
    need_arch = any(p.lower().endswith(".lua") for p in dirty_paths)

    failures = []
    if need_i18n:
        r = subprocess.run(
            [sys.executable, str(repo_root / "tools" / "check_i18n.py")],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if r.returncode != 0:
            failures.append(f"[check_i18n.py rc={r.returncode}]\n{(r.stdout + r.stderr).strip()}")

    if need_arch:
        r = subprocess.run(
            [sys.executable, str(repo_root / "tools" / "check_arch.py")],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if r.returncode != 0:
            failures.append(f"[check_arch.py rc={r.returncode}]\n{(r.stdout + r.stderr).strip()}")

    if failures:
        emit({
            "decision": "continue",
            "reason": "检测到本次修改触发的快速门禁失败，请修复后再结束：\n" + "\n\n".join(failures),
        })
        return

    emit({"decision": "allow"})


if __name__ == "__main__":
    main()
