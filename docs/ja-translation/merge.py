#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
合并日语翻译结果 → 生成 App 词书 assets/ja_<lv>.json。

输入：docs/ja-translation/input/ja_<lv>.json    （id/word/kana/romaji/meaningEn/exampleJa/exampleEn）
      docs/ja-translation/output/ja_<lv>.out.json（id/meaningZh/exampleZh）
输出：app/src/main/assets/ja_<lv>.json          （id/kanji/kana/romaji/meaningZh/example/exampleZh/jlpt）

只处理「output 存在且 id 全覆盖、meaningZh 无空」的级别，逐级独立。
用法：在仓库根目录 `python docs/ja-translation/merge.py`
打印：每级完成情况；末行 COMPLETE=n5,n4,... 供上层判断哪些级别可注册。
"""
import json, os, sys

LEVELS = ["n5", "n4", "n3", "n2", "n1"]
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INDIR = os.path.join(ROOT, "docs", "ja-translation", "input")
OUTDIR = os.path.join(ROOT, "docs", "ja-translation", "output")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

def has_kanji(s):
    return any(0x4E00 <= ord(c) <= 0x9FFF for c in s)

def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)

complete = []
for lv in LEVELS:
    inp_p = os.path.join(INDIR, "ja_%s.json" % lv)
    out_p = os.path.join(OUTDIR, "ja_%s.out.json" % lv)
    if not os.path.exists(out_p):
        print("%s: 缺 output，跳过" % lv.upper())
        continue
    inp = load(inp_p)
    out = {x["id"]: x for x in load(out_p)}
    inids = [x["id"] for x in inp]
    missing = [i for i in inids if i not in out]
    empty_m = [i for i in inids if i in out and not (out[i].get("meaningZh") or "").strip()]
    if missing or empty_m:
        print("%s: 未完成 —— 缺译%d 空释义%d，跳过" % (lv.upper(), len(missing), len(empty_m)))
        continue
    cards = []
    for x in inp:
        o = out[x["id"]]
        word = x["word"]
        cards.append({
            "id": x["id"],
            "kanji": word if has_kanji(word) else "",
            "kana": x["kana"],
            "romaji": x["romaji"],
            "meaningZh": (o.get("meaningZh") or "").strip(),
            "example": x.get("exampleJa", ""),
            "exampleZh": (o.get("exampleZh") or "").strip(),
            "jlpt": lv.upper(),
        })
    dst = os.path.join(ASSETS, "ja_%s.json" % lv)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(cards, f, ensure_ascii=False, separators=(",", ":"))
    # 立即回读校验
    chk = load(dst)
    print("%s: 写入 %d 词 -> assets/ja_%s.json" % (lv.upper(), len(chk), lv))
    complete.append(lv)

print("COMPLETE=" + ",".join(complete))
