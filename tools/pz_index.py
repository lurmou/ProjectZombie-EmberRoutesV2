#!/usr/bin/env python3
"""Project Zomboid B42 bytecode class hierarchy & call-graph SQLite builder/query tool."""
import argparse
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = Path(os.environ.get("PZ_INDEX_DB", str(REPO_ROOT / ".cache" / "pz_b42.sqlite")))
DEFAULT_JAR = os.environ.get(
    "PZ_JAR",
    r"D:\Software\GameApp\steam\steamapps\common\ProjectZomboid\projectzomboid.jar",
)


def connect_ro(db_path: Path) -> sqlite3.Connection:
    if not db_path.exists():
        print(f"Database not found: {db_path}\nRun: python tools/pz_index.py build", file=sys.stderr)
        sys.exit(2)
    return sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)


def resolve_class(conn: sqlite3.Connection, raw: str) -> str:
    norm = raw.replace(".", "/").strip("/")
    exact = conn.execute("SELECT name FROM classes WHERE name = ?", (norm,)).fetchone()
    if exact:
        return exact[0]
    suffix = f"%/{norm}"
    matches = [r[0] for r in conn.execute("SELECT name FROM classes WHERE name LIKE ? ORDER BY name", (suffix,)).fetchall()]
    if len(matches) == 1:
        return matches[0]
    if len(matches) > 1:
        print(f"Ambiguous class '{raw}', matches:", file=sys.stderr)
        for m in matches[:20]:
            print(f"  {m}", file=sys.stderr)
        sys.exit(2)
    # Fallback substring search for inner classes e.g. GlobalObject
    sub = [r[0] for r in conn.execute("SELECT name FROM classes WHERE name LIKE ? ORDER BY name LIMIT 20", (f"%{norm}%",)).fetchall()]
    print(f"Class not found: '{raw}'" + (f". Did you mean: {sub}" if sub else ""), file=sys.stderr)
    sys.exit(2)


def cmd_build(args: argparse.Namespace) -> None:
    jar_path = Path(args.jar)
    if not jar_path.exists():
        print(f"JAR not found: {jar_path}", file=sys.stderr)
        sys.exit(2)
    db_path = Path(args.db)
    db_path.parent.mkdir(parents=True, exist_ok=True)
    dumper = Path(__file__).resolve().parent / "PzBytecodeDump.java"

    t0 = time.perf_counter()
    with tempfile.TemporaryDirectory(prefix="pz_dump_", dir=str(db_path.parent)) as tmp:
        tmp_dir = Path(tmp)
        proc = subprocess.run(
            ["java", str(dumper), str(jar_path), str(tmp_dir)],
            capture_output=True, text=True, encoding="utf-8", errors="replace"
        )
        if proc.returncode != 0:
            print(proc.stdout, proc.stderr, file=sys.stderr)
            sys.exit(proc.returncode)
        print(proc.stdout.strip())

        tmp_db = db_path.with_suffix(".sqlite.tmp")
        if tmp_db.exists():
            tmp_db.unlink()
        conn = sqlite3.connect(str(tmp_db))
        cur = conn.cursor()
        cur.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE classes (
            name TEXT PRIMARY KEY,
            super_name TEXT,
            interfaces TEXT,
            flags INTEGER
        );
        CREATE TABLE methods (
            class_name TEXT,
            method_name TEXT,
            desc TEXT,
            flags INTEGER
        );
        CREATE TABLE calls (
            caller_class TEXT,
            caller_method TEXT,
            caller_desc TEXT,
            opcode TEXT,
            callee_owner TEXT,
            callee_name TEXT,
            callee_desc TEXT
        );
        """)
        with open(tmp_dir / "classes.tsv", "r", encoding="utf-8") as f:
            cur.executemany("INSERT INTO classes VALUES (?, ?, ?, ?)", (l.rstrip("\n").split("\t") for l in f))
        with open(tmp_dir / "methods.tsv", "r", encoding="utf-8") as f:
            cur.executemany("INSERT INTO methods VALUES (?, ?, ?, ?)", (l.rstrip("\n").split("\t") for l in f))
        call_rows = 0
        with open(tmp_dir / "calls.tsv", "r", encoding="utf-8") as f:
            batch = []
            for l in f:
                batch.append(l.rstrip("\n").split("\t"))
                call_rows += 1
                if len(batch) >= 50000:
                    cur.executemany("INSERT INTO calls VALUES (?, ?, ?, ?, ?, ?, ?)", batch)
                    batch.clear()
            if batch:
                cur.executemany("INSERT INTO calls VALUES (?, ?, ?, ?, ?, ?, ?)", batch)
        cur.executescript("""
        CREATE INDEX idx_classes_super ON classes(super_name);
        CREATE INDEX idx_methods_cls ON methods(class_name, method_name);
        CREATE INDEX idx_methods_name ON methods(method_name);
        CREATE INDEX idx_calls_callee ON calls(callee_owner, callee_name, callee_desc);
        CREATE INDEX idx_calls_callee_name ON calls(callee_name);
        CREATE INDEX idx_calls_caller ON calls(caller_class, caller_method);
        """)
        conn.commit()
        conn.close()
        shutil.move(str(tmp_db), str(db_path))

    dt = time.perf_counter() - t0
    size_mb = db_path.stat().st_size / (1024 * 1024)
    print(f"Built {db_path} in {dt:.2f}s | calls={call_rows} | size={size_mb:.2f} MB")


def cmd_subclasses(args: argparse.Namespace) -> None:
    conn = connect_ro(Path(args.db))
    cls_name = resolve_class(conn, args.cls)
    check_methods = [m.strip() for m in args.methods.split(",") if m.strip()] if args.methods else []
    rows = conn.execute("""
    WITH RECURSIVE sub(name, depth) AS (
        SELECT name, 1 FROM classes WHERE super_name = ?
        UNION ALL
        SELECT c.name, s.depth + 1 FROM classes c JOIN sub s ON c.super_name = s.name
    )
    SELECT depth, name FROM sub ORDER BY depth, name
    """, (cls_name,)).fetchall()
    print(f"Transitive subclasses of {cls_name}: {len(rows)}")
    for depth, name in rows:
        if check_methods:
            marks = []
            for m in check_methods:
                cnt = conn.execute(
                    "SELECT COUNT(*) FROM methods WHERE class_name = ? AND method_name = ?",
                    (name, m),
                ).fetchone()[0]
                marks.append(f"{m}={'Y' if cnt > 0 else '-'}")
            print(f"  depth={depth} {name}  [{', '.join(marks)}]")
        else:
            print(f"  depth={depth} {name}")
    conn.close()


def cmd_callers(args: argparse.Namespace) -> None:
    conn = connect_ro(Path(args.db))
    cls_name = resolve_class(conn, args.cls)
    sql = """
    SELECT DISTINCT caller_class, caller_method, caller_desc, opcode, callee_desc
    FROM calls
    WHERE callee_owner = ? AND callee_name = ?
    """
    params: list[str] = [cls_name, args.method]
    if args.desc:
        sql += " AND callee_desc LIKE ?"
        params.append(f"%{args.desc}%")
    sql += " ORDER BY caller_class, caller_method LIMIT ?"
    params.append(args.limit)
    rows = conn.execute(sql, params).fetchall()
    print(f"Callers of {cls_name}.{args.method}" + (f" (desc~{args.desc})" if args.desc else "") + f": {len(rows)}")
    for r in rows:
        print(f"  [{r[3]}] {r[0]}.{r[1]}{r[2]} -> {r[4]}")
    if not rows:
        alt = conn.execute(
            "SELECT DISTINCT method_name, desc FROM methods WHERE class_name = ? AND lower(method_name) LIKE ? ORDER BY method_name",
            (cls_name, f"%{args.method.lower()}%"),
        ).fetchall()
        if alt:
            print(f"Hint — case-insensitive methods on {cls_name}:")
            for mn, md in alt[:15]:
                print(f"  {mn}{md}")
    conn.close()


def cmd_callees(args: argparse.Namespace) -> None:
    conn = connect_ro(Path(args.db))
    cls_name = resolve_class(conn, args.cls)
    sql = """
    SELECT DISTINCT opcode, callee_owner, callee_name, callee_desc
    FROM calls
    WHERE caller_class = ? AND caller_method = ?
    """
    params: list = [cls_name, args.method]
    if args.filter:
        sql += " AND callee_owner LIKE ?"
        params.append(f"{args.filter.replace('.', '/')}%")
    sql += " ORDER BY callee_owner, callee_name LIMIT ?"
    params.append(args.limit)
    rows = conn.execute(sql, params).fetchall()
    print(f"Outgoing calls from {cls_name}.{args.method}: {len(rows)}")
    for op, owner, name, desc in rows:
        print(f"  [{op}] {owner}.{name}{desc}")
    conn.close()


def cmd_methods(args: argparse.Namespace) -> None:
    conn = connect_ro(Path(args.db))
    cls_name = resolve_class(conn, args.cls)
    sql = "SELECT method_name, desc, flags FROM methods WHERE class_name = ?"
    params: list = [cls_name]
    if args.like:
        sql += " AND lower(method_name) LIKE ?"
        params.append(f"%{args.like.lower()}%")
    sql += " ORDER BY method_name, desc LIMIT ?"
    params.append(args.limit)
    rows = conn.execute(sql, params).fetchall()
    print(f"Methods of {cls_name}: {len(rows)}")
    for mn, md, fl in rows:
        print(f"  {mn}{md}  (flags=0x{fl:x})")
    conn.close()


def cmd_sql(args: argparse.Namespace) -> None:
    conn = connect_ro(Path(args.db))
    rows = conn.execute(args.query).fetchall()
    for r in rows[: args.limit]:
        print(r)
    if len(rows) > args.limit:
        print(f"... ({len(rows)} total rows, truncated to {args.limit})")
    conn.close()


def main() -> None:
    p = argparse.ArgumentParser(description="PZ B42 bytecode SQLite index & query tool")
    p.add_argument("--db", default=str(DEFAULT_DB), help="SQLite DB path")
    sub = p.add_subparsers(dest="cmd", required=True)

    pb = sub.add_parser("build", help="Parse projectzomboid.jar and rebuild SQLite index")
    pb.add_argument("--jar", default=DEFAULT_JAR)
    pb.set_defaults(func=cmd_build)

    ps = sub.add_parser("subclasses", help="List transitive subclasses and optional method overrides")
    ps.add_argument("cls")
    ps.add_argument("--methods", default="", help="Comma-separated method names to check override presence")
    ps.set_defaults(func=cmd_subclasses)

    pc = sub.add_parser("callers", help="Find exact bytecode callers of Class.method")
    pc.add_argument("cls")
    pc.add_argument("method")
    pc.add_argument("--desc", default="")
    pc.add_argument("--limit", type=int, default=200)
    pc.set_defaults(func=cmd_callers)

    po = sub.add_parser("callees", help="List outgoing calls from Class.method")
    po.add_argument("cls")
    po.add_argument("method")
    po.add_argument("--filter", default="", help="Owner prefix filter e.g. zombie/characters/")
    po.add_argument("--limit", type=int, default=250)
    po.set_defaults(func=cmd_callees)

    pm = sub.add_parser("methods", help="List methods on Class")
    pm.add_argument("cls")
    pm.add_argument("--like", default="")
    pm.add_argument("--limit", type=int, default=200)
    pm.set_defaults(func=cmd_methods)

    pq = sub.add_parser("sql", help="Run read-only SQL query")
    pq.add_argument("query")
    pq.add_argument("--limit", type=int, default=200)
    pq.set_defaults(func=cmd_sql)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
