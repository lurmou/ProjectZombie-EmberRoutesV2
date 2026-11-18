#!/usr/bin/env python3
"""Verify 100% CN/EN key parity, placeholder parity (%1..%9), and forbid Java printf specifiers (%d/%s/%f)."""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MOD_LUA = ROOT / "Contents" / "mods" / "EmberRoutes" / "42" / "media" / "lua"
TRANS_DIR = MOD_LUA / "shared" / "Translate"

GETTEXT_RE = re.compile(r'getText\s*\(\s*["\']([^"\']+)["\']')
BAD_PRINTF_RE = re.compile(r"%(?:\.\d+)?[dsf]")
POS_ARG_RE = re.compile(r"%(\d+)")


def load_lang(lang: str) -> dict[str, str]:
    merged: dict[str, str] = {}
    lang_dir = TRANS_DIR / lang
    if not lang_dir.is_dir():
        print(f"[check_i18n] MISSING language directory: {lang_dir}", file=sys.stderr)
        sys.exit(1)
    for jf in sorted(lang_dir.glob("*.json")):
        data = json.loads(jf.read_text(encoding="utf-8"))
        for k, v in data.items():
            if not isinstance(v, str) or not v.strip():
                print(f"[check_i18n] Empty translation value for {lang}/{jf.name}:{k}", file=sys.stderr)
                sys.exit(1)
            if BAD_PRINTF_RE.search(v):
                print(
                    f"[check_i18n] Forbidden printf specifier in {lang}/{jf.name}:{k} -> '{v}' "
                    "(use %1, %2... for B42 Translator.getText)",
                    file=sys.stderr,
                )
                sys.exit(1)
            merged[k] = v
    return merged


def main() -> int:
    cn = load_lang("CN")
    en = load_lang("EN")

    only_cn = sorted(set(cn) - set(en))
    only_en = sorted(set(en) - set(cn))
    if only_cn or only_en:
        print(f"[check_i18n] Key parity mismatch! only_CN={only_cn}, only_EN={only_en}", file=sys.stderr)
        return 1

    for k in sorted(cn):
        p_cn = set(POS_ARG_RE.findall(cn[k]))
        p_en = set(POS_ARG_RE.findall(en[k]))
        if p_cn != p_en:
            print(f"[check_i18n] Placeholder mismatch on '{k}': CN={p_cn} vs EN={p_en}", file=sys.stderr)
            return 1

    missing_refs: list[tuple[str, str]] = []
    for lua_file in sorted(MOD_LUA.rglob("*.lua")):
        if "Translate" in lua_file.parts:
            continue
        content = lua_file.read_text(encoding="utf-8")
        for m in GETTEXT_RE.finditer(content):
            key = m.group(1)
            if key not in cn:
                missing_refs.append((str(lua_file.relative_to(ROOT)), key))

    if missing_refs:
        for path, key in missing_refs:
            print(f"[check_i18n] Missing translation key '{key}' referenced in {path}", file=sys.stderr)
        return 1

    print(f"[check_i18n] OK: {len(cn)} keys symmetric across CN/EN, %1..%N placeholders & getText() verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
