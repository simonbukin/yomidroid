#!/usr/bin/env python3
"""Download JLPT vocabulary CSVs and build a JSON mapping of expression -> JLPT level.

Sources: https://github.com/stephenmk/yomitan-jlpt-vocab/tree/main/original_data
Output:  app/src/main/assets/jlpt_vocab.json
"""

import csv
import io
import json
import os
import urllib.request

BASE_URL = "https://raw.githubusercontent.com/stephenmk/yomitan-jlpt-vocab/main/original_data"
LEVELS = ["n5", "n4", "n3", "n2", "n1"]

OUTPUT_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "app",
    "src",
    "main",
    "assets",
    "jlpt_vocab.json",
)


def download_csv(level: str) -> str:
    url = f"{BASE_URL}/{level}.csv"
    print(f"Downloading {url} ...")
    with urllib.request.urlopen(url) as resp:
        return resp.read().decode("utf-8")


def level_priority(level: str) -> int:
    """Lower number = harder level = higher priority (N1 > N5)."""
    return int(level[1])


def main() -> None:
    vocab: dict[str, str] = {}
    stats: dict[str, int] = {}

    for level_tag in LEVELS:
        label = level_tag.upper()  # "N5", "N4", ...
        raw = download_csv(level_tag)
        reader = csv.DictReader(io.StringIO(raw))

        count = 0
        for row in reader:
            kanji = row.get("kanji", "").strip()
            kana = row.get("kana", "").strip()
            key = kanji if kanji else kana
            if not key:
                continue

            # Keep the harder (lower-numbered) level when duplicates exist.
            if key in vocab:
                existing_priority = level_priority(vocab[key])
                new_priority = level_priority(label)
                if new_priority < existing_priority:
                    vocab[key] = label
            else:
                vocab[key] = label

            count += 1

        stats[label] = count
        print(f"  {label}: {count} entries parsed")

    # Ensure output directory exists.
    output = os.path.normpath(OUTPUT_PATH)
    os.makedirs(os.path.dirname(output), exist_ok=True)

    with open(output, "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    print()
    print(f"Total unique expressions: {len(vocab)}")
    for label in ["N5", "N4", "N3", "N2", "N1"]:
        final_count = sum(1 for v in vocab.values() if v == label)
        print(f"  {label}: {final_count} (final, after dedup)")
    print(f"Written to {output}")


if __name__ == "__main__":
    main()
