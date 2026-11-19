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

CLIENT_ONLY_PREFIXES = (
    "EmberRoutes/Boot",
    "EmberRoutes/UI",
    "EmberRoutes/Warehouse",
    "EmberRoutes/Npc",
    "EmberRoutes/Work",
    "EmberRoutes/Squad",
    "EmberRoutes/Compat",
    "EmberUI",
)
CORE_DOMAINS = ("Settlement", "Warehouse", "Npc", "Work", "Squad")


def strip_lua_comments(src: str) -> str:
    src = BLOCK_COMMENT_RE.sub("", src)
    return LINE_COMMENT_RE.sub("", src)


def check_lua_boundaries() -> list[str]:
    errors: list[str] = []
    client_dir = LUA_ROOT / "client"
    client_mods: set[str] = set()
    if client_dir.is_dir():
        for p in client_dir.rglob("*.lua"):
            rel = p.relative_to(client_dir).as_posix()[:-4]
            client_mods.add(rel)

    for lf in sorted(LUA_ROOT.rglob("*.lua")):
        if "Translate" in lf.parts:
            continue
        rel = lf.relative_to(LUA_ROOT).as_posix()
        parts = lf.relative_to(LUA_ROOT).parts  # e.g. ('shared', 'EmberRoutes', 'Settlement', 'SettlementStore.lua')
        side = parts[0]
        domain = parts[2] if len(parts) >= 4 else None
        clean = strip_lua_comments(lf.read_text(encoding="utf-8"))
        reqs = REQUIRE_RE.findall(clean)

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

        if domain != "Compat" and "getActivatedMods" in clean:
            errors.append(f"[compat-leak] {rel} must not call getActivatedMods outside Compat/")

        if rel not in ("shared/EmberRoutes/Kernel.lua", "client/EmberRoutes/Boot.lua"):
            if EVENTS_ADD_RE.search(clean):
                errors.append(f"[raw-events] {rel} must register Events via ctx:on instead of Events.*.Add")
            if DISABLE_MENU_WRITE_RE.search(clean):
                errors.append(f"[raw-flag] {rel} must use scope:acquireGlobalFlag instead of writing disableWorldMenu")

    return errors


def check_java_boundaries() -> list[str]:
    errors: list[str] = []
    if not JAVA_ROOT.is_dir():
        return errors
    for jf in sorted(JAVA_ROOT.glob("*.java")):
        if jf.name not in ("Main.java", "EmberRoutesBridge.java") and not jf.name.startswith("Patch_"):
            errors.append(f"[java-root] Non-entry/non-patch class {jf.name} should live in a domain package")
    wh_dir = JAVA_ROOT / "warehouse"
    if wh_dir.is_dir():
        for jf in sorted(wh_dir.rglob("*.java")):
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
    print("[check_arch] OK: Lua & Java layered architecture boundaries verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
