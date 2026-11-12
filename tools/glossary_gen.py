# -*- coding: utf-8 -*-
"""中→日释义表：选词 → LLM 批量生成 → 打包成 .bge。

三个阶段彼此独立、都可续跑：

    # 1. 选词（默认取「我们已有英文释义的词」∩ 万象词频，按词频从高到低）
    python glossary_gen.py select --limit 50000 --out words-ja.txt

    # 2. 生成（每完成一个词就落盘，中断再跑会自动跳过已完成的）
    set DEEPSEEK_API_KEY=sk-xxxx
    python glossary_gen.py run --words words-ja.txt --out gen-ja.jsonl

    # 3. 打包（可选同时导出人眼可读的 tsv）
    python glossary_gen.py build --in gen-ja.jsonl --out glossary-ja-1.0.bge --tsv glossary-ja-1.0.txt

设计约束（和英文包保持一致，便于 Kotlin 侧零改动读取）：

* 释义文本里 `訳|かな`：`|` 左边是日文写法，右边是整词平假名读音；Kotlin 渲染成 `開発(かいはつ)する`。
* 词性沿用 .bge 的 12 个 id（v=2 / n=1 / adj=3 / adv=4 / pron=7 / int=9 / prep=5 / conj=6 / num=8 / part=10）。
* 每条最多 3 个义项，第一条必须是最常用的那个意思。
"""

import argparse
import io
import json
import os
import random
import re
import struct
import sys
import threading
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glossary_pack as gp  # noqa: E402  复用 HEADER / KEY / REC / 词性 id

LANG_JA = 0x00006A61  # 'ja'

POS_BY_LETTER = {
    "n": gp.POS_N, "v": gp.POS_V, "adj": gp.POS_ADJ, "adv": gp.POS_ADV,
    "prep": gp.POS_PREP, "conj": gp.POS_CONJ, "pron": gp.POS_PRON,
    "num": gp.POS_NUM, "int": gp.POS_INTJ, "part": gp.POS_PART, "m": gp.POS_MEAS,
}
POS_MAP = {
    "n": "n", "v": "v", "adj": "adj", "adv": "adv", "prep": "prep", "conj": "conj",
    "pron": "pron", "num": "num", "int": "int", "interj": "int", "part": "part",
    "meas": "m", "m": "m", "aux": "v", "vt": "v", "vi": "v", "adj-na": "adj",
    "adj-i": "adj", "noun": "n", "verb": "v", "expression": "int", "exp": "int",
}

KANA_RE = re.compile(r"^[ぁ-ゖァ-ヺー・〜\u3000 ]+$")
LATIN_RE = re.compile(r"[A-Za-z]")
HAN_RE = re.compile(r"[\u4e00-\u9fff]")
CJK_PUNCT = "　。、，．？！「」『』（）【】…‥・"

PROMPT_SYSTEM = (
    "你是中日词典编辑。用户给你一批中文词，你为每个词给出日语释义。"
    "要求：1) 只输出最常用的 1~2 个义项，最常用的放第一个；"
    "2) 词性用缩写 v/n/adj/adv/pron/int/num/prep/conj/part/meas 之一；"
    "3) 日语写法用新字体、常用汉字，动词写辞书形（如 開発する、食べる），不要加「〜」；"
    "4) 读音 r 用整词的平假名（含する，如 かいはつする），不要罗马字；"
    "5) 不要写中文、英文、例句、括号注释、句末标点；"
    "6) 查不到合适日语说法（人名、方言、生僻专名）时 senses 给空数组。"
)

PROMPT_USER = (
    "请为下面每个中文词给出日语释义，只输出一行 JSON，格式：\n"
    '{{"items":[{{"w":"中文词","s":[{{"p":"v","t":"開発する","r":"かいはつする"}}]}}]}}\n'
    "共 {n} 个词：{words}"
)


# ------------------------------------------------------------------ 选词
def read_bge_keys(path):
    """读 .bge 里的全部 key（只用于取交集，不解析义项）。"""
    with io.open(path, "rb") as f:
        raw = f.read()
    (magic, ver, flags, lang, entry_count, sense_count,
     off_keys, off_recs, off_arena, arena_size, data_sum, build_time) = gp.HEADER.unpack_from(raw, 0)
    if magic != gp.MAGIC:
        raise SystemExit("不是 .bge 文件: %s" % path)
    keys = []
    for i in range(entry_count):
        key_off, key_len, _rec, _cnt = gp.KEY.unpack_from(raw, off_keys + i * gp.KEY.size)
        keys.append(raw[off_arena + key_off: off_arena + key_off + key_len].decode("utf-8"))
    return keys


def load_freq(path):
    """万象词库：词\\t编码\\t词频。同一个词取最大词频。"""
    freq = {}
    with io.open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            word, cnt = parts[0].strip(), parts[2].strip()
            if not word or not cnt.isdigit():
                continue
            v = int(cnt)
            if v > freq.get(word, -1):
                freq[word] = v
    return freq


def cmd_select(args):
    words = read_bge_keys(args.pack)
    print("英文包词条 %d" % len(words))
    freq = load_freq(args.freq) if os.path.exists(args.freq) else {}
    print("词频表 %d 条" % len(freq))
    # 按词频降序；没有词频的排最后（并按字数、字典序，保证可复现）
    words = sorted(words, key=lambda w: (-freq.get(w, 0), len(w), w))
    if args.min_freq > 0:
        words = [w for w in words if freq.get(w, 0) >= args.min_freq]
    if args.limit > 0:
        words = words[: args.limit]
    with io.open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for w in words:
            f.write(w + "\n")
    covered = sum(1 for w in words if w in freq)
    print("已写出 %d 个词 -> %s（其中有词频的 %d）" % (len(words), args.out, covered))


# ------------------------------------------------------------------ 生成
def norm_pos(raw):
    t = (raw or "").strip().lower().rstrip(".")
    return POS_MAP.get(t, "")


def clean_item(obj):
    """把模型返回的一条校验成 (word, [(text, pos), ...])；不合格返回 None。"""
    w = (obj.get("w") or "").strip()
    if not w:
        return None
    senses = []
    seen = set()
    for s in (obj.get("s") or [])[:3]:
        t = (s.get("t") or "").strip()
        r = (s.get("r") or "").strip()
        if not t:
            continue
        if LATIN_RE.search(t) or LATIN_RE.search(r):
            continue
        if len(t) > 24:
            continue
        t = t.strip(CJK_PUNCT)
        r = r.strip(CJK_PUNCT)
        # 假名只留平假名/长音符/中点，其余丢掉（模型偶尔混进汉字或空格）
        if r and not KANA_RE.match(r):
            r = "".join(ch for ch in r if KANA_RE.match(ch))
        text = t if not r else (t + "|" + r)
        if text in seen:
            continue
        seen.add(text)
        senses.append((text, POS_BY_LETTER.get(norm_pos(s.get("p")), gp.POS_NONE)))
    return (w, senses)


def post_json(url, payload, api_key, timeout=120):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + api_key,
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def chat_batch(base, model, api_key, words):
    prompt = PROMPT_USER.format(n=len(words), words="、".join(words))
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": PROMPT_SYSTEM},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.2,
        "max_tokens": 4096,
        "response_format": {"type": "json_object"},
    }
    last = None
    for attempt in range(4):
        try:
            resp = post_json(base.rstrip("/") + "/chat/completions", payload, api_key)
            content = resp["choices"][0]["message"]["content"]
            obj = json.loads(content)
            return obj.get("items") or []
        except Exception as e:  # noqa: BLE001  网络/限流/JSON 坏掉都重试
            last = e
            time.sleep(1.5 * (attempt + 1) + random.random())
    print("  ! 批次失败（%d 个词）：%s" % (len(words), last))
    return []


def cmd_run(args):
    api_key = os.environ.get(args.key_env, "").strip()
    if not api_key:
        raise SystemExit("环境变量 %s 为空，先设置 API key" % args.key_env)

    with io.open(args.words, encoding="utf-8") as f:
        todo = [w.strip() for w in f if w.strip()]
    done = set()
    if os.path.exists(args.out):
        with io.open(args.out, encoding="utf-8") as f:
            for line in f:
                try:
                    done.add(json.loads(line)["w"])
                except Exception:  # noqa: BLE001
                    pass
    todo = [w for w in todo if w not in done]
    print("待处理 %d 个词（已完成 %d）" % (len(todo), len(done)))
    if args.limit > 0:
        todo = todo[: args.limit]
        print("本次只跑前 %d 个" % len(todo))

    batches = [todo[i:i + args.batch] for i in range(0, len(todo), args.batch)]
    lock = threading.Lock()
    out = io.open(args.out, "a", encoding="utf-8", newline="\n")
    counter = {"words": 0, "senses": 0, "empty": 0, "batch": 0}
    t0 = time.time()

    def work(batch):
        items = chat_batch(args.base, args.model, api_key, batch)
        rows = []
        for it in items:
            r = clean_item(it)
            if r is None:
                continue
            w, senses = r
            if w not in batch:  # 模型偶尔改写词形，直接丢
                continue
            rows.append({"w": w, "s": [{"t": t, "p": p} for t, p in senses]})
        got = {r["w"] for r in rows}
        with lock:
            for r in rows:
                out.write(json.dumps(r, ensure_ascii=False) + "\n")
                counter["words"] += 1
                counter["senses"] += len(r["s"])
                if not r["s"]:
                    counter["empty"] += 1
            counter["batch"] += 1
            out.flush()
            el = time.time() - t0
            if counter["batch"] % 5 == 0:
                rate = counter["words"] / el * 60 if el > 0 else 0
                print("  批次 %d/%d  词 %d  义项 %d  空 %d  %.1f 词/分"
                      % (counter["batch"], len(batches), counter["words"],
                         counter["senses"], counter["empty"], rate))

    threads = []
    for b in batches:
        while sum(1 for t in threads if t.is_alive()) >= args.workers:
            time.sleep(0.2)
        th = threading.Thread(target=work, args=(b,))
        th.start()
        threads.append(th)
    for th in threads:
        th.join()
    out.close()
    print("完成：%d 词 / %d 义项 / 空 %d，用时 %.1f 分钟"
          % (counter["words"], counter["senses"], counter["empty"], (time.time() - t0) / 60))


# ------------------------------------------------------------------ 打包
def cmd_build(args):
    entries = {}
    with io.open(args.inp, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            w = r["w"]
            senses = []
            for s in r.get("s") or []:
                t = s["t"]
                pos = int(s.get("p") or 0)
                senses.append((t, pos, 0, 0))
            if senses:
                entries[w] = senses
    if not entries:
        raise SystemExit("没有可用条目")

    ordered = sorted(entries.items(), key=lambda kv: kv[0].encode("utf-8"))
    arena = bytearray()
    key_meta = []
    for word, _ in ordered:
        kb = word.encode("utf-8")
        off = len(arena)
        arena += kb
        key_meta.append([off, len(kb), 0, 0])
    recs = []
    for i, (word, senses) in enumerate(ordered):
        key_meta[i][2] = len(recs)
        key_meta[i][3] = len(senses)
        for (text, pos, l1, l2) in senses:
            tb = text.encode("utf-8")
            off = len(arena)
            arena += tb
            recs.append([off, len(tb), pos, l1, l2, 0])

    off_keys = gp.HEADER.size
    off_recs = off_keys + len(key_meta) * gp.KEY.size
    off_arena = off_recs + len(recs) * gp.REC.size
    keys_blob = bytearray()
    for off, klen, recstart, cnt in key_meta:
        keys_blob += gp.KEY.pack(off, klen, recstart, cnt)
    recs_blob = bytearray()
    for off, tlen, pos, l1, l2, rsv in recs:
        recs_blob += gp.REC.pack(off, tlen, pos, l1, l2, rsv)

    import hashlib
    digest = hashlib.sha256(bytes(keys_blob) + bytes(recs_blob) + bytes(arena)).digest()
    data_sum = struct.unpack("<I", digest[:4])[0]
    header = gp.HEADER.pack(
        gp.MAGIC, gp.VERSION, 0, LANG_JA, len(key_meta), len(recs),
        off_keys, off_recs, off_arena, len(arena), data_sum, int(time.time()),
    )
    with io.open(args.out, "wb") as f:
        f.write(header)
        f.write(bytes(keys_blob))
        f.write(bytes(recs_blob))
        f.write(bytes(arena))
    size = os.path.getsize(args.out)
    print("打包完成：%d 词 / %d 义项 / %.2f MB -> %s"
          % (len(key_meta), len(recs), size / 1048576.0, args.out))

    if args.tsv:
        with io.open(args.tsv, "w", encoding="utf-8", newline="\n") as f:
            f.write("# 词\t[词性. ]译文|假名\n")
            for word, senses in ordered:
                cols = []
                for (text, pos, _l1, _l2) in senses:
                    name = gp.POS_NAME.get(pos, "")
                    cols.append((name + " " + text).strip() if name else text)
                f.write(word + "\t" + "\t".join(cols) + "\n")
        print("已导出 %s" % args.tsv)


# ------------------------------------------------------------------ CLI
DEFAULT_FREQ = r"C:\Users\Administrator\Desktop\Ai_Work\DSH_harness\_scratch\pyprobe\user\dicts\jichu.pro.dict.yaml"
DEFAULT_PACK = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "..", "..", "dist", "glossary-en-cedict-1.0.bge")


def main():
    ap = argparse.ArgumentParser(description="中→日释义表：选词/生成/打包")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("select", help="选词")
    s.add_argument("--pack", default=os.path.normpath(DEFAULT_PACK))
    s.add_argument("--freq", default=DEFAULT_FREQ)
    s.add_argument("--min-freq", type=int, default=0)
    s.add_argument("--limit", type=int, default=0)
    s.add_argument("--out", required=True)
    s.set_defaults(func=cmd_select)

    r = sub.add_parser("run", help="调 LLM 生成")
    r.add_argument("--words", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--batch", type=int, default=40)
    r.add_argument("--workers", type=int, default=4)
    r.add_argument("--limit", type=int, default=0)
    r.add_argument("--model", default="deepseek-chat")
    r.add_argument("--base", default="https://api.deepseek.com")
    r.add_argument("--key-env", default="DEEPSEEK_API_KEY")
    r.set_defaults(func=cmd_run)

    b = sub.add_parser("build", help="打包成 .bge")
    b.add_argument("--in", dest="inp", required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--tsv", default="")
    b.set_defaults(func=cmd_build)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
