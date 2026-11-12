# -*- coding: utf-8 -*-
"""glossary_pack.py — 把 CC-CEDICT 编译成 BambooIME 的候选英文词库（.bge）

设计原则（2026-11-12 与用户定稿）：
  * 打包层「只丢不是英语的东西」，其余一个义项都不丢。
    丢：整条只有交叉引用的词（variant of / see / same as …）、纯拼音残留、
        完全重复的义项、清理后为空的义项。
    留：全部义项、词性线索、语体标签、领域标签、量词、or 并列、长释义、surname。
  * 「显示什么、显示多少」由 App 设置决定，不在打包时裁剪。

.bge 布局（全部小端）
  0   4   magic  'BGE1'
  4   2   version
  6   2   flags        bit0 = 义项里含行内注（〔CL: …〕）
  8   4   langCode     'en' -> 0x00006E65
  12  4   entryCount
  16  4   senseCount
  20  4   offKeys
  24  4   offRecs
  28  4   offArena
  32  4   arenaSize
  36  4   dataSum      arena+keys+recs 的 sha256 前 4 字节
  40  8   buildTime    unix 秒
  48  ... KEYS  entryCount × 12 B，按 key 的 UTF-8 字节升序
          u32 keyOff / u16 keyLen / u32 recStart / u16 senseCount
      ... RECS  senseCount × 12 B
          u32 textOff / u16 textLen / u8 pos / u8 label1 / u8 label2 / u8 reserved
      ... ARENA key 与义项文本共用
"""

import io
import os
import re
import struct
import sys
import time
import hashlib
import argparse

MAGIC = b"BGE1"
VERSION = 1
HEADER = struct.Struct("<4sHHIIIIIIIIQ")  # 48 B
KEY = struct.Struct("<IHI H".replace(" ", ""))  # 12 B: u32 off, u16 len, u32 rec, u16 cnt
REC = struct.Struct("<IHBBBB")  # 12 B: u32 off, u16 len, u8 pos, u8 l1, u8 l2, u8 rsv
LANG_EN = 0x00006E65

# 交叉引用行：整条只有这些内容的词直接丢掉（没有英文词义）
XREF = re.compile(
    r"^(variant of|old variant of|see |same as|abbr\. for|original form of|"
    r"contracted form of|erhua variant of|used in |also written )"
)

# 词性 id（和 Kotlin 侧 Pos 常量一一对应）
POS_NONE, POS_N, POS_V, POS_ADJ, POS_ADV, POS_PREP, POS_CONJ, POS_PRON, POS_NUM, POS_INTJ, POS_PART, POS_MEAS = range(12)

# 标签白名单（小写、去掉尾部句点后比对）。索引即 .bge 里的 label id，0 保留给「无标签」。
LABELS = [
    # —— 语体 / 场合（默认显示）
    "literary", "coll", "slang", "dialect", "old", "archaic", "honorific", "derog",
    "euphem", "loanword", "fig", "lit", "onom", "internet slang", "bound form",
    # —— 领域 / 类别（默认不显示）
    "computing", "math", "chemistry", "physics", "medicine", "tcm", "buddhism",
    "sports", "botany", "geology", "anatomy", "idiom", "name", "tw",
    "bird species of china",
]
LABEL_POLITE = set(LABELS[:15])  # 前 15 个算「语体」，其余算「领域」

LINE_RE = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+/(.*)/\s*$")
PAREN_RE = re.compile(r"^\(([^()]{1,30})\)\s*")
TAIL_PAREN_RE = re.compile(r"\s*\(([^()]{1,30})\)\s*$")
MID_PAREN_RE = re.compile(r"\s*\(([^()]{1,30})\)")
PINYIN_RE = re.compile(r"\s*\[[a-zA-Z0-9 :.]*\]")
CL_RE = re.compile(r"CL:([^\s;]*(?:\|[^\s;]*)?)\s*")

# 清理后会剩下这些「不是词义」的碎片，整条丢掉
JUNK_RE = re.compile(
    r"^(Taiwan pr\.?|also pr\.?|lit\.?|fig\.?|onom\.?|archaic\.?|coll\.?|slang\.?"
    r"|abbr\.?|erhua variant.*|used in .*|variant of .*|see .*|same as .*)$",
    re.I,
)
HAS_LETTER = re.compile(r"[A-Za-z]")


def norm_label(text):
    t = text.strip().lower().rstrip(".")
    return t


def label_id(text):
    t = norm_label(text)
    if t in LABELS:
        return LABELS.index(t) + 1
    return 0


def clean_sense(raw):
    """返回 (text, pos, label1, label2) 或 None（清理后为空）。"""
    s = raw.strip()
    if not s:
        return None

    # 1) 行首标签，最多取两个
    labels = []
    while len(labels) < 2:
        m = PAREN_RE.match(s)
        if not m:
            break
        lid = label_id(m.group(1))
        if lid == 0:
            break  # 不是白名单标签，说明是 (of a tiger) 这类内容说明，保留在正文里
        labels.append(lid)
        s = s[m.end():]

    # 1.5) 行尾标签（CEDICT 里也很常见：context menu (computing)）
    while len(labels) < 2:
        m = TAIL_PAREN_RE.search(s)
        if not m:
            break
        lid = label_id(m.group(1))
        if lid == 0:
            break
        labels.append(lid)
        s = s[:m.start()]

    # 1.8) 句中标语（如 "to throw brickbats (slang)"）：只认白名单里的词，其余括号原样保留
    if len(labels) < 2:
        kept = []
        pos_i = 0
        for m in MID_PAREN_RE.finditer(s):
            if len(labels) >= 2:
                break
            lid = label_id(m.group(1))
            if lid == 0:
                continue
            kept.append((m.start(), m.end(), lid))
        if kept:
            out = []
            last = 0
            for st, en, lid in kept:
                out.append(s[last:st])
                last = en
                labels.append(lid)
            out.append(s[last:])
            s = " ".join("".join(out).split())

    # 2) 量词 -> 行内注 〔CL: 个〕（显示层可按开关去掉）
    cl_note = ""
    m = CL_RE.search(s)
    if m:
        spec = m.group(1)
        if "|" in spec:                       # 繁|简 -> 取简体
            spec = spec.split("|", 1)[1]
        spec = spec.replace("|", "/").strip()
        if spec:
            cl_note = "〔CL: %s〕" % spec
        s = (s[:m.start()] + s[m.end():])

    # 3) 拼音残留
    s = PINYIN_RE.sub("", s)

    # 4) 词性线索（顺便把标记从正文里拿掉，显示层再按开关加回去）
    pos = POS_NONE
    m = re.match(r"^to\s+", s)
    if m and len(s) > m.end() + 1:
        pos = POS_V
        s = s[m.end():]
    else:
        m = re.match(r"^(a|an|the)\s+", s)
        if m and len(s) > m.end() + 1:
            pos = POS_N
            s = s[m.end():]

    # 5) 归一化
    s = re.sub(r"\s+", " ", s).strip()
    s = s.strip(" ;,，；")
    if not s:
        return None
    if cl_note:
        s = s + cl_note

    # 6) 丢掉「不是词义」的碎片（Taiwan pr. / lit. / 只有中文 / 只有标点）
    body = re.sub(r"〔[^〕]*〕", "", s).strip()
    if not body:
        return None
    if JUNK_RE.match(body):
        return None
    if not HAS_LETTER.search(body):
        return None

    l1 = labels[0] if len(labels) > 0 else 0
    l2 = labels[1] if len(labels) > 1 else 0
    return (s, pos, l1, l2)


def load_cedict(path):
    """word -> [line_senses, ...]；保留 CEDICT 的行边界（同一词不同读音是不同行）。"""
    words = {}
    order = []
    total = 0
    for raw in io.open(path, encoding="utf-8"):
        if not raw or raw[0] == "#":
            continue
        m = LINE_RE.match(raw.rstrip("\n"))
        if not m:
            continue
        total += 1
        word = m.group(2)
        senses = [x.strip() for x in m.group(4).split("/") if x.strip()]
        if word not in words:
            words[word] = []
            order.append(word)
        words[word].append(senses)
    return words, order, total


def build(cedict_path, out_path, manifest_path):
    words, order, raw_lines = load_cedict(cedict_path)

    entries = []          # (key_bytes, [(text,pos,l1,l2), ...])
    dropped_xref = 0
    dropped_dup = 0
    dropped_sense = 0
    for word in order:
        seen = set()
        real = []
        # 同一词的多行（不同读音）按「义项多的行靠前」排：多个义项的那一行通常是主读音。
        # 例如「中」: 中[zhong1] 有 6 个义项，中[Zhong1](姓) 只有 2 个 -> 主读音先显示。
        for line in sorted(words[word], key=len, reverse=True):
            for raw in line:
                if raw in seen:
                    dropped_dup += 1
                    continue
                seen.add(raw)
                if XREF.match(raw):
                    continue
                c = clean_sense(raw)
                if c is None:
                    dropped_sense += 1
                    continue
                real.append(c)
        if not real:
            dropped_xref += 1
            continue
        entries.append((word, real))

    entries.sort(key=lambda kv: kv[0].encode("utf-8"))

    # —— ARENA：先 key，再义项文本
    arena = bytearray()
    key_meta = []
    for word, senses in entries:
        kb = word.encode("utf-8")
        off = len(arena)
        arena += kb
        key_meta.append([off, len(kb), 0, len(senses)])

    recs = []
    total_senses = 0
    for i, (word, senses) in enumerate(entries):
        key_meta[i][2] = len(recs)
        for (text, pos, l1, l2) in senses:
            tb = text.encode("utf-8")
            off = len(arena)
            arena += tb
            recs.append([off, len(tb), pos, l1, l2, 0])
            total_senses += 1

    arena_size = len(arena)
    off_keys = HEADER.size
    off_recs = off_keys + len(entries) * KEY.size
    off_arena = off_recs + len(recs) * REC.size

    keys_blob = bytearray()
    for off, klen, recstart, cnt in key_meta:
        keys_blob += KEY.pack(off, klen, recstart, cnt)
    recs_blob = bytearray()
    for off, tlen, pos, l1, l2, rsv in recs:
        recs_blob += REC.pack(off, tlen, pos, l1, l2, rsv)

    digest = hashlib.sha256(bytes(keys_blob) + bytes(recs_blob) + bytes(arena)).digest()
    data_sum = struct.unpack("<I", digest[:4])[0]

    flags = 1 if any("〔CL:" in s[0] for _, ss in entries for s in ss) else 0
    header = HEADER.pack(
        MAGIC, VERSION, flags, LANG_EN, len(entries), total_senses,
        off_keys, off_recs, off_arena, arena_size, data_sum, int(time.time()),
    )

    with open(out_path, "wb") as f:
        f.write(header)
        f.write(keys_blob)
        f.write(recs_blob)
        f.write(arena)

    size = os.path.getsize(out_path)
    with io.open(manifest_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("BambooIME 候选英文词库清单\n")
        f.write("==========================\n\n")
        f.write("文件        : %s\n" % os.path.basename(out_path))
        f.write("大小        : %d 字节 (%.2f MB)\n" % (size, size / 1048576.0))
        f.write("格式        : %s v%d\n" % (MAGIC.decode(), VERSION))
        f.write("语言        : en\n")
        f.write("来源        : CC-CEDICT (cedict_1_0_ts_utf-8_mdbg)\n")
        f.write("许可        : CC BY-SA 4.0（改编物同许可，见 LICENSE-CC-BY-SA-4.0.txt）\n")
        f.write("生成时间    : %s\n\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
        f.write("原始释义行  : %d\n" % raw_lines)
        f.write("收下词条    : %d\n" % len(entries))
        f.write("收下义项    : %d\n" % total_senses)
        f.write("丢弃·纯交叉引用条目 : %d\n" % dropped_xref)
        f.write("丢弃·重复义项       : %d\n" % dropped_dup)
        f.write("丢弃·清理后为空     : %d\n" % dropped_sense)
        f.write("arena       : %d 字节\n" % arena_size)
        f.write("dataSum     : 0x%08X\n\n" % data_sum)
        f.write("词性 id（RECS.pos）\n")
        for i, name in enumerate(["none", "n", "v", "adj", "adv", "prep", "conj", "pron", "num", "interj", "part", "meas"]):
            f.write("  %2d = %s\n" % (i, name))
        f.write("\n标签 id（RECS.label1/label2，0 = 无）\n")
        for i, name in enumerate(LABELS):
            group = "语体" if name in LABEL_POLITE else "领域"
            f.write("  %2d = %-22s [%s]\n" % (i + 1, name, group))
        f.write("\n抽样（前 30 条）\n")
        for word, senses in entries[:30]:
            parts = []
            for text, pos, l1, l2 in senses:
                parts.append("(%s/%d/%d) %s" % (pos, l1, l2, text))
            f.write("  %s  ->  %s\n" % (word, " | ".join(parts)))

    return dict(entries=len(entries), senses=total_senses, size=size,
                dropped_xref=dropped_xref, dropped_dup=dropped_dup,
                dropped_sense=dropped_sense, raw_lines=raw_lines, data_sum=data_sum)


class Pack(object):
    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            self.buf = f.read()
        (self.magic, self.version, self.flags, self.lang, self.entry_count,
         self.sense_count, self.off_keys, self.off_recs, self.off_arena,
         self.arena_size, self.data_sum, self.build_time) = HEADER.unpack_from(self.buf, 0)
        assert self.magic == MAGIC, "不是 .bge 文件（magic=%r）" % self.magic
        body = self.buf[HEADER.size:]
        self.real_sum = struct.unpack("<I", hashlib.sha256(body).digest()[:4])[0]

    def key_at(self, i):
        off, klen, recstart, cnt = KEY.unpack_from(self.buf, self.off_keys + i * KEY.size)
        kb = self.buf[self.off_arena + off: self.off_arena + off + klen]
        return kb.decode("utf-8"), recstart, cnt

    def senses_at(self, recstart, cnt):
        out = []
        for j in range(recstart, recstart + cnt):
            off, tlen, pos, l1, l2, _ = REC.unpack_from(self.buf, self.off_recs + j * REC.size)
            out.append((self.buf[self.off_arena + off: self.off_arena + off + tlen].decode("utf-8"), pos, l1, l2))
        return out

    def lookup(self, word):
        kb = word.encode("utf-8")
        lo, hi = 0, self.entry_count - 1
        while lo <= hi:
            mid = (lo + hi) // 2
            k, recstart, cnt = self.key_at(mid)
            if k == word:
                return self.senses_at(recstart, cnt)
            if k < word:
                lo = mid + 1
            else:
                hi = mid - 1
        return None


POS_NAME = {0: "", 1: "n.", 2: "v.", 3: "adj.", 4: "adv.", 5: "prep.", 6: "conj.",
            7: "pron.", 8: "num.", 9: "interj.", 10: "part.", 11: "量"}


def fmt(word, senses):
    chunks = []
    for text, pos, l1, l2 in senses:
        head = POS_NAME.get(pos, "")
        tags = []
        if l1:
            tags.append(LABELS[l1 - 1])
        if l2:
            tags.append(LABELS[l2 - 1])
        t = ("(%s) " % ", ".join(tags)) if tags else ""
        chunks.append((head + " " if head else "") + t + text)
    return "%s → %s" % (word, " ｜ ".join(chunks))


def verify(bge_path, words):
    p = Pack(bge_path)
    print("magic=%s v%d flags=%d lang=%s" % (p.magic.decode(), p.version, p.flags, hex(p.lang)))
    print("词条=%d  义项=%d  arena=%d B  文件=%d B (%.2f MB)" % (
        p.entry_count, p.sense_count, p.arena_size, len(p.buf), len(p.buf) / 1048576.0))
    ok = (p.data_sum == p.real_sum)
    print("dataSum 0x%08X -> %s" % (p.data_sum, "OK" if ok else "MISMATCH 0x%08X" % p.real_sum))
    # 单调性检查（保证二分查找成立）
    prev = None
    bad = 0
    for i in range(p.entry_count):
        k, _, _ = p.key_at(i)
        if prev is not None and not (prev < k):
            bad += 1
        prev = k
    print("key 递增检查: %s" % ("OK" if bad == 0 else "FAILED %d 处" % bad))
    print("")
    for w in words:
        s = p.lookup(w)
        print(("  " + fmt(w, s)) if s else ("  %s -> 未收录" % w))
    return p


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build")
    b.add_argument("cedict")
    b.add_argument("out")
    b.add_argument("--manifest", default=None)

    v = sub.add_parser("verify")
    v.add_argument("bge")
    v.add_argument("words", nargs="*")

    a = ap.parse_args()
    if a.cmd == "build":
        manifest = a.manifest or (os.path.splitext(a.out)[0] + ".txt")
        r = build(a.cedict, a.out, manifest)
        print("原始行 %d -> 词条 %d / 义项 %d" % (r["raw_lines"], r["entries"], r["senses"]))
        print("丢弃: 纯交叉引用 %d, 重复义项 %d, 清理后为空 %d" % (
            r["dropped_xref"], r["dropped_dup"], r["dropped_sense"]))
        print("文件 %d B (%.2f MB)  dataSum 0x%08X" % (r["size"], r["size"] / 1048576.0, r["data_sum"]))
        print("清单 %s" % manifest)
    else:
        verify(a.bge, a.words or ["打", "我们", "计算机", "你好", "北京", "谢谢", "中", "一丘之貉", "上下文菜单", "令堂"])


if __name__ == "__main__":
    main()
