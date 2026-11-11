#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文曲星数据包编译器（离线）

把 my_tips 格式的 .txt 提示文件编译成 .bwx 二进制包。

源格式（与 my_tips.lua 一致）：

    key=
    {正文，可以跨行，直到第一个 } 结束}

索引规则与 my_tips.lua 对齐：

    K  用 key 查，上屏整段正文
    S  用正文按标点切出的分句查，上屏整段正文（分句长度 >= min_seg_len，且分句 != key）
    F  用去空白后的整段正文查，上屏 = key（双向）

包格式（全部小端）：

    header   48 B : magic "BWX1" / u16 version / u16 flags / u32 keyCount
                    u32 recCount / u32 contentCount / u32 arenaSize / u32 hashCap
                    u32 offHash / offKeys / offRecs / offContents / offArena
    hash     hashCap * u32    开放寻址表，0 = 空槽，否则 keyId + 1
    keys     keyCount * 12 B  u32 keyOff / u16 keyLen / u16 recCount / u32 recStart
    recs     recCount * 4 B   u32 (type << 30 | contentId)，type 1=K 2=S 3=F
    contents contentCount * 8 B  u32 off / u32 len（指向 arena）
    arena    原文 UTF-8 拼接区（key 与 content 共用，content 去重后只存一份）

用法：

    python tools/wenquxing_pack.py build --out out/wenquxing-packs mingju.txt=诗词名句 ...
    python tools/wenquxing_pack.py verify out/wenquxing-packs/mingju.bwx
    python tools/wenquxing_pack.py bench out/wenquxing-packs/mingju.bwx

只依赖标准库。
"""

from __future__ import annotations

import argparse
import mmap
import struct
import sys
import time
from pathlib import Path

MAGIC = b"BWX1"
VERSION = 1
HEADER = struct.Struct("<4sHHIIIIIIIIII")

REC_KEY, REC_SEG, REC_FULL = 1, 2, 3

# 与 my_tips.lua 的默认值保持一致
MIN_KEY_LEN = 2
MIN_CONTENT_LEN = 4
MIN_CONTENT_LEN_NOKEY_NOSEP = 2
MIN_SEG_LEN = 4

PUNCT = set("，。？！；：、,.?!;:|｜")


# ---------------------------------------------------------------- 解析


def remove_whitespace(s: str) -> str:
    return "".join(ch for ch in s if ch != "\u3000" and ord(ch) > 0x20)


def has_separator(s: str) -> bool:
    for ch in s:
        if ch in PUNCT or ch == "\u3000" or ord(ch) <= 0x20:
            return True
    return False


def extract_sentences(text: str) -> list[str]:
    out: list[str] = []
    buf: list[str] = []
    for ch in text:
        if ch in PUNCT or ch == "\u3000" or ord(ch) <= 0x20:
            if buf:
                out.append("".join(buf))
                buf = []
        else:
            buf.append(ch)
    if buf:
        out.append("".join(buf))
    return out


def parse_entries(text: str):
    """等价于 Lua 的 content:gmatch("([^=\\n]+)=%s*%{([^}]*)}")。"""
    pos = 0
    n = len(text)
    while True:
        eq = text.find("=", pos)
        if eq < 0:
            return
        # key 段：从上一个位置到 '='，且不能含 '=' 或 '\n'
        start = eq
        while start > pos and text[start - 1] not in "=\n":
            start -= 1
        key = text[start:eq].strip()
        rest = eq + 1
        while rest < n and text[rest] in " \t\r\n":
            rest += 1
        if rest < n and text[rest] == "{":
            close = text.find("}", rest + 1)
            if close > 0:
                value = text[rest + 1:close]
                if key:
                    yield key, value
                pos = close + 1
                continue
        pos = eq + 1


def build_records(path: Path):
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    records: dict[str, dict[tuple[int, str], None]] = {}
    entries = 0

    def add(key: str, rtype: int, commit: str):
        bucket = records.get(key)
        if bucket is None:
            bucket = records[key] = {}
        bucket[(rtype, commit)] = None

    for key, value in parse_entries(text):
        entries += 1
        if len(key) < MIN_KEY_LEN:
            continue
        full_ns = remove_whitespace(value)
        plain_block = key not in value and not has_separator(value)
        floor = MIN_CONTENT_LEN_NOKEY_NOSEP if plain_block else MIN_CONTENT_LEN
        if len(full_ns) < floor:
            continue

        add(key, REC_KEY, value)
        if full_ns:
            add(full_ns, REC_FULL, key)
        for seg in extract_sentences(value):
            if seg and seg != key and len(seg) >= MIN_SEG_LEN:
                add(seg, REC_SEG, value)

    return records, entries


def fnv1a32(data: bytes) -> int:
    h = 0x811C9DC5
    for b in data:
        h = ((h ^ b) * 0x01000193) & 0xFFFFFFFF
    return h


def next_pow2(n: int) -> int:
    cap = 1
    while cap < n:
        cap <<= 1
    return cap


# ---------------------------------------------------------------- 写包


def write_pack(path: Path, records: dict[str, dict[tuple[int, str], None]], title: str = "") -> dict:
    keys = list(records.keys())
    arena = bytearray()
    arena_index: dict[str, tuple[int, int]] = {}

    def put(s: str) -> tuple[int, int]:
        hit = arena_index.get(s)
        if hit is not None:
            return hit
        raw = s.encode("utf-8")
        off = len(arena)
        arena.extend(raw)
        arena_index[s] = (off, len(raw))
        return off, len(raw)

    # 标题放在字符串区最前面，长度记在 header 的 flags（u16）里
    title_len = 0
    if title:
        title_off, title_len = put(title)
        assert title_off == 0

    key_rows: list[tuple[int, int, int, int]] = []  # off, len, recCount, recStart
    rec_rows: list[int] = []
    contents: dict[str, int] = {}  # content -> contentId
    content_rows: list[tuple[int, int]] = []
    key_id = {k: i for i, k in enumerate(keys)}

    for key in keys:
        key_off, key_len = put(key)
        rec_start = len(rec_rows)
        for (rtype, commit) in records[key]:
            if rtype == REC_FULL:
                # F 记录上屏的是 key 本身，contentId 直接指向 keys 区
                cid = key_id[commit]
            else:
                cid = contents.get(commit)
                if cid is None:
                    cid = len(content_rows)
                    contents[commit] = cid
                    content_rows.append(put(commit))
            rec_rows.append((rtype << 30) | cid)
        key_rows.append((key_off, key_len, len(records[key]), rec_start))

    hash_cap = max(8, next_pow2(max(1, int(len(keys) * 1.5))))
    table = [0] * hash_cap
    for kid, key in enumerate(keys):
        raw = key.encode("utf-8")
        slot = fnv1a32(raw) & (hash_cap - 1)
        while table[slot]:
            slot = (slot + 1) & (hash_cap - 1)
        table[slot] = kid + 1

    off_hash = HEADER.size
    off_keys = off_hash + hash_cap * 4
    off_recs = off_keys + len(key_rows) * 12
    off_contents = off_recs + len(rec_rows) * 4
    off_arena = off_contents + len(content_rows) * 8

    with path.open("wb") as f:
        f.write(HEADER.pack(
            MAGIC, VERSION, title_len,
            len(key_rows), len(rec_rows), len(content_rows), len(arena), hash_cap,
            off_hash, off_keys, off_recs, off_contents, off_arena,
        ))
        f.write(struct.pack(f"<{hash_cap}I", *table))
        for off, klen, rcount, rstart in key_rows:
            f.write(struct.pack("<IHHI", off, klen, rcount, rstart))
        f.write(struct.pack(f"<{len(rec_rows)}I", *rec_rows) if rec_rows else b"")
        for off, length in content_rows:
            f.write(struct.pack("<II", off, length))
        f.write(arena)

    return {
        "keys": len(key_rows),
        "recs": len(rec_rows),
        "contents": len(content_rows),
        "arena": len(arena),
        "bytes": path.stat().st_size,
    }


# ---------------------------------------------------------------- 读包


class Pack:
    def __init__(self, path: Path):
        self.path = path
        self.fh = path.open("rb")
        self.mm = mmap.mmap(self.fh.fileno(), 0, access=mmap.ACCESS_READ)
        (magic, version, _flags, self.key_count, self.rec_count, self.content_count,
         self.arena_size, self.hash_cap, self.off_hash, self.off_keys,
         self.off_recs, self.off_contents, self.off_arena) = HEADER.unpack_from(self.mm, 0)
        if magic != MAGIC:
            raise ValueError(f"{path}: 不是 BWX1 包")
        if version != VERSION:
            raise ValueError(f"{path}: 版本 {version} 不支持")

    def close(self):
        self.mm.close()
        self.fh.close()

    def _key_row(self, kid: int):
        off = self.off_keys + kid * 12
        key_off, key_len, rec_count, rec_start = struct.unpack_from("<IHHI", self.mm, off)
        data = self.mm[self.off_arena + key_off: self.off_arena + key_off + key_len]
        return data.decode("utf-8"), rec_count, rec_start

    def lookup(self, key: str):
        cap = self.hash_cap
        mask = cap - 1
        slot = fnv1a32(key.encode("utf-8")) & mask
        for _ in range(cap):
            value = struct.unpack_from("<I", self.mm, self.off_hash + slot * 4)[0]
            if value == 0:
                return []
            kid = value - 1
            stored, rec_count, rec_start = self._key_row(kid)
            if stored == key:
                out = []
                for i in range(rec_count):
                    packed = struct.unpack_from("<I", self.mm, self.off_recs + (rec_start + i) * 4)[0]
                    rtype = packed >> 30
                    cid = packed & 0x3FFFFFFF
                    if rtype == REC_FULL:
                        commit, _c, _s = self._key_row(cid)
                        out.append(("F", commit))
                    else:
                        off, length = struct.unpack_from("<II", self.mm, self.off_contents + cid * 8)
                        text = self.mm[self.off_arena + off: self.off_arena + off + length].decode("utf-8")
                        out.append(("K" if rtype == REC_KEY else "S", text))
                return out
            slot = (slot + 1) & mask
        return []


def summarize_text(text: str, n: int = 12) -> str:
    compact = remove_whitespace(text)
    return compact[:n] + ("…" if len(compact) > n else "")


# ---------------------------------------------------------------- 命令


def title_of(path: Path, fallback: str) -> str:
    try:
        text = path.read_text(encoding="utf-8-sig", errors="replace")
    except OSError:
        return fallback
    for line in text.splitlines()[:8]:
        s = line.strip()
        if s.startswith("#"):
            name = s.lstrip("#").strip()
            if name:
                return name
    return fallback


def cmd_build(args):
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    total = 0
    for item in args.packs:
        if "=" in item:
            src, title = item.split("=", 1)
        else:
            src, title = item, None
        src_path = Path(src)
        if not src_path.is_absolute():
            for base in args.search:
                cand = Path(base) / src_path
                if cand.exists():
                    src_path = cand
                    break
        if not src_path.exists():
            print(f"!! 找不到 {src}", file=sys.stderr)
            return 1
        if not title:
            title = title_of(src_path, Path(src).stem)
        t0 = time.perf_counter()
        records, entries = build_records(src_path)
        t1 = time.perf_counter()
        dst = out_dir / (Path(src).stem + ".bwx")
        stats = write_pack(dst, records, title)
        t2 = time.perf_counter()
        total += stats["bytes"]
        print(
            f"{title:<8} {src_path.name:<18} 条目 {entries:>7,}  键 {stats['keys']:>7,}  "
            f"记录 {stats['recs']:>8,}  包 {stats['bytes']/1048576:>6.2f} MB  "
            f"解析 {t1-t0:>5.2f}s  写盘 {t2-t1:>5.2f}s"
        )
    print(f"合计 {total/1048576:.2f} MB")
    return 0


def cmd_verify(args):
    for p in args.packs:
        path = Path(p)
        pack = Pack(path)
        print(f"{path.name}: 键 {pack.key_count:,} 记录 {pack.rec_count:,} "
              f"内容 {pack.content_count:,} 字符串区 {pack.arena_size/1048576:.2f} MB "
              f"哈希表 {pack.hash_cap:,}")
        for sample in args.key or []:
            hits = pack.lookup(sample)
            preview = " | ".join(f"{t}:{summarize_text(c, 16)}" for t, c in hits[:3])
            print(f"   {sample!r} -> {len(hits)} 条  {preview}")
        pack.close()
    return 0


def cmd_bench(args):
    import random
    for p in args.packs:
        path = Path(p)
        pack = Pack(path)
        keys = [pack._key_row(kid)[0] for kid in random.sample(range(pack.key_count),
                                                               min(2000, pack.key_count))]
        t0 = time.perf_counter()
        rounds = 20
        hit = 0
        for _ in range(rounds):
            for k in keys:
                if pack.lookup(k):
                    hit += 1
        dt = time.perf_counter() - t0
        n = rounds * len(keys)
        print(f"{path.name}: {n:,} 次查询 {dt*1000:.1f} ms  "
              f"单次 {dt/n*1e6:.1f} µs  命中 {hit/n*100:.0f}%")
        pack.close()
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="文曲星数据包编译器")
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="把 txt 编译成 .bwx")
    b.add_argument("packs", nargs="+", help="文件[=显示名]")
    b.add_argument("--out", default="out/wenquxing-packs")
    b.add_argument("--search", action="append", default=[],
                   help="源文件搜索目录，可多次指定")
    b.set_defaults(func=cmd_build)

    v = sub.add_parser("verify", help="检查包并抽样查询")
    v.add_argument("packs", nargs="+")
    v.add_argument("--key", action="append", default=[])
    v.set_defaults(func=cmd_verify)

    c = sub.add_parser("bench", help="查询性能测试")
    c.add_argument("packs", nargs="+")
    c.set_defaults(func=cmd_bench)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
