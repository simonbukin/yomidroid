#!/usr/bin/env python3
"""Slim kanjiapi_full.json down to the bundled app asset.

Source: https://github.com/onlyskin/kanjiapi.dev (MIT).
The full dump is ~98 MB (kanjis + readings + words); we only need the kanjis
block. Run from repo root:

    python3 tools/prepare_kanji_data.py

Outputs:
    app/src/main/assets/kanji_data.json   (~1.3 MB, short-keyed JSON)
"""

import json
import os
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO_ROOT, "kanjiapi_full.json")
ASSETS = os.path.join(REPO_ROOT, "app", "src", "main", "assets")
OUT_KANJI = os.path.join(ASSETS, "kanji_data.json")


def main() -> int:
    if not os.path.isfile(SRC):
        print(f"ERROR: source file not found: {SRC}", file=sys.stderr)
        print("Place kanjiapi_full.json at the repo root.", file=sys.stderr)
        return 1

    print(f"Reading {SRC} ...")
    with open(SRC, "r", encoding="utf-8") as f:
        data = json.load(f)

    kanjis = data["kanjis"]
    print(f"  kanjis: {len(kanjis):,}")

    entries = {}
    for char, v in kanjis.items():
        slim = {
            "s": v["stroke_count"],
            "m": v["meanings"],
            "on": v["on_readings"],
            "kun": v["kun_readings"],
        }
        if v.get("grade") is not None:
            slim["g"] = v["grade"]
        if v.get("jlpt") is not None:
            slim["j"] = v["jlpt"]
        if v.get("name_readings"):
            slim["nr"] = v["name_readings"]
        if v.get("freq_mainichi_shinbun") is not None:
            slim["f"] = v["freq_mainichi_shinbun"]
        if v.get("heisig_en"):
            slim["h"] = v["heisig_en"]
        entries[char] = slim

    kanji_out = {"version": 1, "entries": entries}

    os.makedirs(ASSETS, exist_ok=True)
    with open(OUT_KANJI, "w", encoding="utf-8") as f:
        json.dump(kanji_out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"Wrote {OUT_KANJI} ({os.path.getsize(OUT_KANJI):,} bytes)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
