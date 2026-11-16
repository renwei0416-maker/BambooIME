#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「我们相对上游改了什么」的清单。

用法（在 repo/ 目录下）：
    python tools/upstream-diff.py                # 写到 docs/UPSTREAM-DIFF.md
    python tools/upstream-diff.py --print        # 只打印

分叉点取环境变量 UPSTREAM_BASE，默认 `59d508e3`
（= 我们第一个提交 `b5d3a195 bamboo: 1.1.0 baseline` 的父提交，上游 Carlson24/LyraIME）。

注意：本机 `github.com` 主机不通，`git fetch origin` 拉不到上游新提交，
所以「上游在分叉点之后又动了什么」这一段由脚本调用 api.github.com 生成
（失败就跳过，不影响主体清单）。
"""
import json
import os
import subprocess
import sys
import urllib.request

BASE = os.environ.get("UPSTREAM_BASE", "59d508e3")
REPO = "Carlson24/LyraIME"
OUT = os.path.join("docs", "UPSTREAM-DIFF.md")
# 需要人工写「为什么改」的热点文件（其余文件在清单里只列状态与行数）
HOT = [
    "app/src/main/java/com/osfans/trime/ime/core/InputView.kt",
    "app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt",
    "app/src/main/java/com/osfans/trime/core/Rime.kt",
    "app/src/main/java/com/osfans/trime/ime/composition/PreeditUi.kt",
    "app/src/main/java/com/osfans/trime/ime/composition/CandidatesView.kt",
    "app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt",
    "app/src/main/java/com/osfans/trime/ime/keyboard/KeyView.kt",
    "app/src/main/java/com/osfans/trime/ime/keyboard/KeyAction.kt",
    "app/src/main/java/com/osfans/trime/ime/window/BoardWindowManager.kt",
    "app/src/main/java/com/osfans/trime/data/packaging/SchemaPackageManager.kt",
    "app/src/main/java/com/osfans/trime/ui/main/settings/PackageListFragment.kt",
    "app/src/main/java/com/osfans/trime/ui/main/MainFragment.kt",
    "app/src/main/java/com/osfans/trime/ui/main/NavigationRoute.kt",
    "app/build.gradle.kts",
    "gradle.properties",
]


def git(*args):
    return subprocess.run(
        ["git", "-c", "core.quotepath=false", *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout


def local_changes():
    status = {}
    for line in git("diff", "--name-status", "%s..HEAD" % BASE).splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            status[parts[-1]] = parts[0][:1]
    churn = {}
    for line in git("diff", "--numstat", "%s..HEAD" % BASE).splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            add, dele, path = parts
            churn[path] = (int(add) if add.isdigit() else 0, int(dele) if dele.isdigit() else 0)
    return status, churn


def upstream_moves():
    """上游在分叉点之后新增的提交与文件（走 API，失败返回 None）。"""
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/%s/compare/%s...lyraime" % (REPO, BASE),
            headers={"User-Agent": "bambooime", "Accept": "application/vnd.github+json"},
        )
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.load(r)
        return data.get("commits", []), data.get("files", [])
    except Exception as e:  # noqa: BLE001
        print("warn: 取上游增量失败（%s），跳过该节" % e, file=sys.stderr)
        return None


def main():
    status, churn = local_changes()
    if not status:
        print("没有差异？检查 UPSTREAM_BASE=%s" % BASE, file=sys.stderr)
        return 1
    modified = sorted(p for p, s in status.items() if s == "M")
    added = sorted(p for p, s in status.items() if s == "A")
    deleted = sorted(p for p, s in status.items() if s == "D")
    total_add = sum(v[0] for v in churn.values())
    total_del = sum(v[1] for v in churn.values())

    moves = upstream_moves()
    overlap = set()
    if moves:
        upstream_files = {f["filename"] for f in moves[1]}
        overlap = upstream_files & set(status)

    out = []
    out.append("# 相对上游改了哪些文件")
    out.append("")
    out.append("> 本文件由 `python tools/upstream-diff.py` 生成，**不要手改**。每次准备发版前重跑一次。")
    out.append("")
    out.append("- 上游：`https://github.com/Carlson24/LyraIME`（分支 `lyraime`）")
    out.append("- 分叉点：`%s`（我们第一个提交 `b5d3a195` 的父提交）" % BASE)
    out.append("- 提交数：`%s`（`git rev-list --count %s..HEAD`）" % (
        git("rev-list", "--count", "%s..HEAD" % BASE).strip(), BASE))
    out.append("- 变更文件：**%d** 个（修改 %d / 新增 %d / 删除 %d）" % (
        len(status), len(modified), len(added), len(deleted)))
    out.append("- 代码行：**+%d / -%d**" % (total_add, total_del))
    out.append("")

    if moves is not None:
        out.append("## 上游在分叉点之后又动了什么")
        out.append("")
        if not moves[0]:
            out.append("上游 `lyraime` 没有新提交。")
        else:
            out.append("| 提交 | 说明 |")
            out.append("|---|---|")
            for c in moves[0]:
                out.append("| `%s` | %s |" % (c["sha"][:8], c["commit"]["message"].splitlines()[0]))
            out.append("")
            out.append("| 上游改的文件 | 增删 | 与我们重叠 |")
            out.append("|---|---|---|")
            for f in moves[1]:
                mark = "**是**" if f["filename"] in overlap else "否"
                out.append("| `%s` | +%d/-%d | %s |" % (
                    f["filename"], f["additions"], f["deletions"], mark))
        out.append("")

    if overlap:
        out.append("## ⚠ 合并时会冲突的文件")
        out.append("")
        for p in sorted(overlap):
            out.append("- `%s`" % p)
        out.append("")

    out.append("## 我们改过的上游文件（合并冲突只可能出在这些里）")
    out.append("")
    out.append("| 文件 | 增删 |")
    out.append("|---|---|")
    for p in modified:
        a, d = churn.get(p, (0, 0))
        star = " ⭐" if p in HOT else ""
        out.append("| `%s`%s | +%d/-%d |" % (p, star, a, d))
    out.append("")
    out.append("⭐ = 语义改动较大、合并时优先人工过一遍；逐个文件的「为什么改」见 `docs/upstream-patches/`。")
    out.append("")

    out.append("## 我们新增的文件（一般不会冲突）")
    out.append("")
    out.append("<details><summary>%d 个，点开</summary>" % len(added))
    out.append("")
    for p in added:
        out.append("- `%s`" % p)
    out.append("")
    out.append("</details>")
    out.append("")

    out.append("## 我们删掉的上游文件")
    out.append("")
    for p in deleted:
        out.append("- `%s`" % p)
    out.append("")

    text = "\n".join(out) + "\n"
    if "--print" in sys.argv:
        print(text)
    else:
        os.makedirs("docs", exist_ok=True)
        with open(OUT, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("wrote %s (%d chars)" % (OUT, len(text)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
