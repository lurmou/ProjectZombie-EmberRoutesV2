#!/usr/bin/env python3
"""Static architecture boundary checks for EmberRoutesV2 Lua & Java layout."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LUA_ROOT = ROOT / "Contents" / "mods" / "EmberRoutes" / "42" / "media" / "lua"
JAVA_ROOT = ROOT / "java-src" / "src" / "main" / "java" / "emberroutes"

BLOCK_COMMENT_RE = re.compile(r"--\[(=*)\[.*?\]\1\]", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"--[^\n]*")
REQUIRE_RE = re.compile(r"""require\s*\(?\s*["']([^"']+)["']\s*\)?""")
EVENTS_ADD_RE = re.compile(r"\bEvents\s*\.\s*[A-Za-z0-9_]+\s*\.\s*Add\s*\(")
DISABLE_MENU_WRITE_RE = re.compile(r"\bdisableWorldMenu\s*=(?!=)")
GLOBAL_RESET_RE = re.compile(r"\bEmberRoutesJava\s*\.\s*reset\s*\(")
BYPASS_HOT_EVENT_RE = re.compile(
    r":on\s*\(\s*Events\s*\.\s*(OnTick|OnTickEvenPaused|LoadGridsquare|ReuseGridsquare)\b"
)

CLIENT_ONLY_PREFIXES = (
    "EmberRoutes/Boot",
    "EmberRoutes/UI",
    "EmberRoutes/Warehouse",
    "EmberRoutes/Npc",
    "EmberRoutes/Work",
    "EmberRoutes/Squad",
    "EmberRoutes/Caravan",
    "EmberRoutes/Compat",
    "EmberUI",
)
CORE_DOMAINS = ("Settlement", "Warehouse", "Npc", "Work", "Squad", "Caravan")

# 严格域级 DAG 单向拓扑阶：低层域严禁反向依赖高层域
DOMAIN_RANK = {
    "Settlement": 10,
    "Warehouse": 20,
    "Npc": 30,
    "Work": 40,
    "Squad": 50,
    "Caravan": 60,
    "UI": 70,
    "Compat": 80,
}


def strip_lua_comments(src: str) -> str:
    src = BLOCK_COMMENT_RE.sub("", src)
    return LINE_COMMENT_RE.sub("", src)


def extract_target_domain(mod_name: str) -> str | None:
    if not mod_name.startswith("EmberRoutes/"):
        return None
    parts = mod_name.split("/")
    if len(parts) >= 3 and parts[1] in DOMAIN_RANK:
        return parts[1]
    return None


def detect_require_cycles(graph: dict[str, list[str]]) -> list[str]:
    errors: list[str] = []
    state: dict[str, int] = {}  # 0=unvisited, 1=visiting, 2=done
    stack: list[str] = []

    def dfs(node: str) -> None:
        state[node] = 1
        stack.append(node)
        for nxt in graph.get(node, ()):
            st = state.get(nxt, 0)
            if st == 1:
                idx = stack.index(nxt)
                cycle = " -> ".join(stack[idx:] + [nxt])
                errors.append(f"[lua-cycle] Circular require detected: {cycle}")
            elif st == 0 and nxt in graph:
                dfs(nxt)
        stack.pop()
        state[node] = 2

    for node in sorted(graph.keys()):
        if state.get(node, 0) == 0:
            dfs(node)
    return errors


def check_lua_boundaries() -> list[str]:
    errors: list[str] = []
    client_dir = LUA_ROOT / "client"
    client_mods: set[str] = set()
    if client_dir.is_dir():
        for p in client_dir.rglob("*.lua"):
            rel = p.relative_to(client_dir).as_posix()[:-4]
            client_mods.add(rel)

    req_graph: dict[str, list[str]] = {}

    for lf in sorted(LUA_ROOT.rglob("*.lua")):
        if "Translate" in lf.parts:
            continue
        rel = lf.relative_to(LUA_ROOT).as_posix()
        parts = lf.relative_to(LUA_ROOT).parts  # e.g. ('shared', 'EmberRoutes', 'Settlement', 'SettlementStore.lua')
        side = parts[0]
        mod_key = "/".join(parts[1:])[:-4]
        domain = parts[2] if len(parts) >= 4 else None
        clean = strip_lua_comments(lf.read_text(encoding="utf-8"))
        reqs = REQUIRE_RE.findall(clean)
        req_graph[mod_key] = [r for r in reqs if r.startswith("EmberRoutes/")]

        if side == "shared":
            for mod in reqs:
                if mod in client_mods or any(mod == p or mod.startswith(p + "/") for p in CLIENT_ONLY_PREFIXES):
                    errors.append(f"[shared->client] {rel} must not require client module '{mod}'")

        if rel == "shared/EmberRoutes/Kernel.lua":
            for mod in reqs:
                if mod.startswith("EmberRoutes/") or mod == "EmberUI" or mod.startswith("EmberUI/"):
                    errors.append(f"[kernel-purity] Kernel.lua must not require '{mod}'")

        if domain in CORE_DOMAINS:
            for mod in reqs:
                if (
                    mod.startswith("EmberRoutes/UI")
                    or mod.startswith("EmberRoutes/Compat")
                    or mod == "EmberUI"
                    or mod.startswith("EmberUI/")
                ):
                    errors.append(f"[domain->ui/compat] {rel} must not require '{mod}'")

        if domain in DOMAIN_RANK:
            src_rank = DOMAIN_RANK[domain]
            for mod in reqs:
                target_domain = extract_target_domain(mod)
                if target_domain and DOMAIN_RANK[target_domain] > src_rank:
                    errors.append(
                        f"[domain-dag] {rel} ({domain}) must not require higher-layer domain '{target_domain}' ({mod})"
                    )

        if domain != "Compat" and "getActivatedMods" in clean:
            errors.append(f"[compat-leak] {rel} must not call getActivatedMods outside Compat/")

        if rel != "shared/EmberRoutes/Kernel.lua":
            if GLOBAL_RESET_RE.search(clean):
                errors.append(
                    f"[raw-global-reset] {rel} must not call EmberRoutesJava.reset(); use domain-scoped reset*()"
                )

        if rel not in ("shared/EmberRoutes/Kernel.lua", "client/EmberRoutes/Boot.lua"):
            if EVENTS_ADD_RE.search(clean):
                errors.append(f"[raw-events] {rel} must register Events via Kernel/scope instead of Events.*.Add")
            if DISABLE_MENU_WRITE_RE.search(clean):
                errors.append(f"[raw-flag] {rel} must use scope:acquireGlobalFlag instead of writing disableWorldMenu")
            if BYPASS_HOT_EVENT_RE.search(clean):
                errors.append(
                    f"[bypass-pipeline] {rel} must use Kernel tick stages / Java Patch_IsoChunk instead of scope:on hot events"
                )

    errors.extend(detect_require_cycles(req_graph))
    return errors


def check_java_boundaries() -> list[str]:
    errors: list[str] = []
    if not JAVA_ROOT.is_dir():
        return errors
    for jf in sorted(JAVA_ROOT.glob("*.java")):
        if jf.name not in ("Main.java", "EmberRoutesBridge.java") and not jf.name.startswith("Patch_"):
            errors.append(f"[java-root] Non-entry/non-patch class {jf.name} should live in a domain package")
    for sub in sorted(JAVA_ROOT.iterdir()):
        if not sub.is_dir():
            continue
        for jf in sorted(sub.rglob("*.java")):
            text = jf.read_text(encoding="utf-8")
            if "emberroutes.EmberRoutesBridge" in text or "Patch_" in text:
                errors.append(f"[java-layer] {jf.relative_to(ROOT)} must not depend on Bridge or Patch_*")
    return errors


def main() -> int:
    errors = check_lua_boundaries() + check_java_boundaries()
    if errors:
        for e in errors:
            print(f"[check_arch] VIOLATION: {e}", file=sys.stderr)
        return 1
    print("[check_arch] OK: Lua & Java layered DAG architecture boundaries verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
