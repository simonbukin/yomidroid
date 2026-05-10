#!/usr/bin/env python3
"""
Build app/src/main/assets/external-grammar/itazuraneko-index.json (schema v2).

Reads entirely from local mirrors — no live HTTP:
  - ITAZURA_MIRROR: ~/Projects/programming/reference/itazuraneko-archive/
                    kenrick95.github.io/itazuraneko/
  - HJG URLs are rewritten to Wayback Machine (live site is dead, and HJG
    content is scanned book images — no headline to extract).

Per-source data extracted:
  - DOJG:       short headline gloss from <table class="dojgtab"> row 1 td 2
                (factual label like "Give", "Daringly; boldly; dare to~")
  - Donnatoki:  short headline from first <p> after <h2 class="donnaconcept">
  - Tae Kim:    lesson title from index (already English)
  - Imabi:      lesson title from index (already English)
  - HJG:        no headline; URL rewritten to Wayback

Output schema v2 — see itazuraneko-index.json. Includes per-source `localPath`
for future in-app reader bundling.

Usage:
    scripts/grammar-ingest/.venv/bin/python scripts/grammar-ingest/scrape_itazuraneko.py
"""

from __future__ import annotations

import json
import os
import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse, urljoin, unquote

from bs4 import BeautifulSoup

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
HOME = Path.home()

ITAZURA_MIRROR = HOME / "Projects/programming/reference/itazuraneko-archive/kenrick95.github.io/itazuraneko/"
GRAMMAR_DIR = ITAZURA_MIRROR / "grammar"

OUT_PATH = REPO_ROOT / "app/src/main/assets/external-grammar/itazuraneko-index.json"

# Index pages (read from local mirror)
INDEX_PAGES = {
    "masterreference": GRAMMAR_DIR / "masterreference.html",
    "taekim": GRAMMAR_DIR / "taekim.html",
    "imabi": GRAMMAR_DIR / "imabi.html",
}

BASE_URL = "https://kenrick95.github.io/itazuraneko/grammar/"

# Map masterreference's "reference" column → our source key
REF_TO_SOURCE = {
    "1. Basic Japanese Grammar": "dojg",
    "2. Intermediate Japanese Grammar": "dojg",
    "3. Advanced Japanese Grammar": "dojg",
    "4. HJG": "hjg",
    "5. 日本語表現文型辞典": "donnatoki",
}

# Preferred display-headline source order
HEADLINE_PRIORITY = ["dojg", "donnatoki", "taekim", "imabi", "masterref", "hjg"]

# Per-resource button display order
SOURCE_PRIORITY = {
    "gamegengo": 0, "dojg": 1, "donnatoki": 2,
    "taekim": 3, "imabi": 4, "hjg": 5, "masterref": 6,
}

WAYBACK_PREFIX = "https://web.archive.org/web/2025/"


# ---------- Pattern normalization ----------

_LEVEL_PREFIX_RE = re.compile(r"^[㊞㊤㊥]\s*")  # ㊞㊤㊥
_DISAMBIG_SUFFIX_RE = re.compile(r"\(\s*\d+\s*\)\s*$")
_WS_RE = re.compile(r"\s+")


def normalize_pattern_key(s: str) -> str:
    s = unicodedata.normalize("NFKC", s)
    s = _LEVEL_PREFIX_RE.sub("", s)
    s = _DISAMBIG_SUFFIX_RE.sub("", s)
    s = _WS_RE.sub("", s)
    return s.strip()


def clean_pattern_display(s: str) -> str:
    s = unicodedata.normalize("NFKC", s)
    s = _LEVEL_PREFIX_RE.sub("", s)
    s = _DISAMBIG_SUFFIX_RE.sub("", s)
    return s.strip()


def normalize_headline(s: str) -> str:
    """Tidy whitespace and trailing punctuation on extracted headlines."""
    s = _WS_RE.sub(" ", s).strip()
    # Strip terminal Japanese ellipsis variants and trailing dots/spaces
    s = re.sub(r"[。….\s]+$", "", s)
    return s


# ---------- URL / local path helpers ----------

def url_to_local_path(url: str) -> Optional[Path]:
    """Map an itazuraneko URL to its local mirror path. Returns None if unmappable."""
    parsed = urlparse(url)
    if parsed.netloc != "kenrick95.github.io":
        return None
    # /itazuraneko/grammar/dojg/dojgpages/<file>.html
    path = unquote(parsed.path)
    prefix = "/itazuraneko/"
    if not path.startswith(prefix):
        return None
    rel = path[len(prefix):]
    p = ITAZURA_MIRROR / rel
    return p


def hjg_rewrite(url: str) -> str:
    """Rewrite a dead core6000 HJG URL to its Wayback Machine snapshot URL."""
    if "core6000.neocities.org" in url:
        return WAYBACK_PREFIX + url
    return url


# ---------- Headline extractors per source ----------

# Cache parsed donnatoki files (each has many entries)
_donnatoki_cache: dict[Path, BeautifulSoup] = {}


def extract_dojg_headline(local_path: Path) -> Optional[str]:
    """Headline is the second <td> of the first row of the first <table class='dojgtab'>."""
    try:
        soup = BeautifulSoup(local_path.read_text(encoding="utf-8"), "html.parser")
        tbl = soup.find("table", class_="dojgtab")
        if not tbl:
            return None
        first_row = tbl.find("tr")
        if not first_row:
            return None
        cells = first_row.find_all("td")
        if len(cells) < 2:
            return None
        text = cells[1].get_text(" ", strip=True)
        return normalize_headline(text) or None
    except FileNotFoundError:
        return None
    except Exception:
        return None


def extract_donnatoki_headline(local_path: Path, anchor: str) -> Optional[str]:
    """For a donnatoki URL with anchor (e.g. ...#あいだ), find the first <p>
    immediately after the <h2 class='donnaconcept' id='anchor'>."""
    try:
        if local_path not in _donnatoki_cache:
            _donnatoki_cache[local_path] = BeautifulSoup(
                local_path.read_text(encoding="utf-8"), "html.parser"
            )
        soup = _donnatoki_cache[local_path]
        h2 = soup.find("h2", id=anchor)
        if not h2:
            return None
        # Walk forward through siblings until we hit a <p>
        sib = h2
        for _ in range(6):
            sib = sib.find_next_sibling()
            if sib is None:
                return None
            if sib.name == "p":
                return normalize_headline(sib.get_text(" ", strip=True)) or None
        return None
    except FileNotFoundError:
        return None
    except Exception:
        return None


def extract_taekim_imabi_headline(title: str) -> Optional[str]:
    """Tae Kim and Imabi titles already contain the English description.
    Strip the leading "第N課:" / number prefix for a cleaner headline."""
    if not title:
        return None
    t = title.strip()
    # "第12課: The Subject Marker が" → "The Subject Marker が"
    t = re.sub(r"^第\s*\d+\s*課[:\s\-]*", "", t)
    # "12 Conjugating to the past state-of-being" → "Conjugating to the past..."
    t = re.sub(r"^\d+\s+", "", t)
    return normalize_headline(t) or None


# ---------- Parsers ----------

@dataclass
class RawEntry:
    pattern: str       # cleaned display pattern (no level marker, no (N) suffix)
    source: str        # "dojg" | "donnatoki" | "hjg" | "taekim" | "imabi" | "masterref"
    title: str         # original link text or lesson title
    url: str           # absolute URL (link-out target)
    local_path: Optional[str] = None  # repo-relative under ITAZURA_MIRROR (for future bundling)
    headline: Optional[str] = None    # per-source short English gloss


def parse_masterreference(html: str) -> list[RawEntry]:
    """Walks the master cross-reference table → DOJG, donnatoki, HJG entries."""
    soup = BeautifulSoup(html, "html.parser")
    body = soup.find("div", class_="bodymargin")
    tbl = body.find("table", class_="sortable")
    tds = tbl.find_all("td")

    entries: list[RawEntry] = []
    for i in range(0, len(tds), 2):
        if i + 1 >= len(tds):
            break
        pattern_cell, ref_cell = tds[i], tds[i + 1]
        a = pattern_cell.find("a", href=True)
        if not a:
            continue

        ref_text = _WS_RE.sub(" ", ref_cell.get_text(" ", strip=True)).strip()
        source = REF_TO_SOURCE.get(ref_text)
        if source is None:
            continue

        raw_title = a.get_text(strip=True)
        if not raw_title:
            continue

        href = a["href"].strip()
        url = urljoin(BASE_URL, href)

        # HJG → rewrite URL to Wayback (core6000 site is dead)
        if source == "hjg":
            url = hjg_rewrite(url)
            local_path = None
            headline = None
        else:
            # Try to resolve to local mirror file (for headline + bundling hint)
            lp = url_to_local_path(url)
            local_path = str(lp.relative_to(ITAZURA_MIRROR)) if lp and lp.exists() else None
            headline = None
            if local_path and source == "dojg":
                headline = extract_dojg_headline(lp)
            elif local_path and source == "donnatoki":
                # Anchor is the entry's headword
                anchor = urlparse(url).fragment or ""
                if anchor:
                    headline = extract_donnatoki_headline(lp, anchor)

        entries.append(RawEntry(
            pattern=clean_pattern_display(raw_title),
            source=source,
            title=raw_title,
            url=url,
            local_path=local_path,
            headline=headline,
        ))
    return entries


def parse_taekim(html: str) -> list[RawEntry]:
    soup = BeautifulSoup(html, "html.parser")
    body = soup.find("div", class_="bodymargin")

    entries: list[RawEntry] = []
    seen = set()
    base_url = BASE_URL + "taekim.html"
    base_local = "grammar/taekim.html"

    # Local mirror uses `taekim.html#N ...`; live site uses bare `#N ...`. Accept both.
    for a in body.find_all("a", href=True):
        href = a["href"]
        m = re.match(r"^(?:taekim\.html)?#(\d+)\s+(.*)$", href)
        if not m:
            continue
        # Only count anchors inside <ul>/<li> (the TOC); skip in-body cross-links
        if not a.find_parent("ul"):
            continue
        lesson_num = int(m.group(1))
        if lesson_num == 0:
            continue
        title = a.get_text(strip=True)
        if not title or href in seen:
            continue
        seen.add(href)

        full_url = base_url + href
        headline = extract_taekim_imabi_headline(title)
        entries.append(RawEntry(
            pattern=title,
            source="taekim",
            title=f"{lesson_num}. {title}",
            url=full_url,
            local_path=f"{base_local}{href}",
            headline=headline,
        ))
    return entries


def parse_imabi(html: str) -> list[RawEntry]:
    soup = BeautifulSoup(html, "html.parser")
    body = soup.find("div", class_="bodymargin")

    entries: list[RawEntry] = []
    seen_urls = set()

    for a in body.find_all("a", href=True):
        href = a["href"].strip()
        if not href.startswith("imabi/"):
            continue
        title = a.get_text(strip=True)
        if not title or len(title) < 2:
            continue
        url = urljoin(BASE_URL, href)
        if url in seen_urls:
            continue
        seen_urls.add(url)

        headline = extract_taekim_imabi_headline(title)
        # Local path under itazuraneko mirror
        local_path = f"grammar/{unquote(href)}"

        entries.append(RawEntry(
            pattern=title,
            source="imabi",
            title=title,
            url=url,
            local_path=local_path,
            headline=headline,
        ))
    return entries


# ---------- Merge + output ----------

def pick_display_headline(per_source_headlines: dict[str, str]) -> Optional[str]:
    for src in HEADLINE_PRIORITY:
        h = per_source_headlines.get(src)
        if h:
            return h
    return None


def merge_entries(raws: list[RawEntry]) -> list[dict]:
    groups: dict[str, dict] = {}
    for r in raws:
        key = normalize_pattern_key(r.pattern)
        if not key:
            continue
        g = groups.setdefault(key, {
            "key": key,
            "pattern": r.pattern,
            "sources": [],
            "_headlines": {},
        })
        # Dedup by (source, url) within the entry
        if any(s["source"] == r.source and s["url"] == r.url for s in g["sources"]):
            continue
        source_dict = {
            "source": r.source,
            "title": r.title,
            "url": r.url,
        }
        if r.local_path:
            source_dict["localPath"] = r.local_path
        if r.headline:
            source_dict["headline"] = r.headline
            # Record first non-empty headline per source for the entry's display pick
            g["_headlines"].setdefault(r.source, r.headline)
        g["sources"].append(source_dict)

    out = []
    for key in sorted(groups.keys()):
        g = groups[key]
        g["sources"].sort(key=lambda s: SOURCE_PRIORITY.get(s["source"], 99))
        entry = {
            "pattern": g["pattern"],
            "sources": g["sources"],
        }
        display_headline = pick_display_headline(g["_headlines"])
        if display_headline:
            entry["headline"] = display_headline
        out.append(entry)
    return out


def main() -> int:
    # Sanity check
    if not ITAZURA_MIRROR.exists():
        print(f"ERROR: itazuraneko mirror not found at {ITAZURA_MIRROR}", file=sys.stderr)
        return 2

    all_raw: list[RawEntry] = []
    for name, local in INDEX_PAGES.items():
        print(f"[{name}] reading {local.relative_to(HOME)}")
        if not local.exists():
            print(f"  WARN: missing {local}", file=sys.stderr)
            continue
        html = local.read_text(encoding="utf-8")
        parser = {"masterreference": parse_masterreference,
                  "taekim": parse_taekim,
                  "imabi": parse_imabi}[name]
        entries = parser(html)
        # Headline coverage report
        with_h = sum(1 for e in entries if e.headline)
        print(f"  parsed {len(entries)} entries, {with_h} with headline ({with_h * 100 // max(len(entries), 1)}%)")
        all_raw.extend(entries)

    print(f"\nTotal raw entries: {len(all_raw)}")
    merged = merge_entries(all_raw)
    print(f"After dedup: {len(merged)} unique patterns")

    # Source distribution
    src_counts = defaultdict(int)
    src_headlines = defaultdict(int)
    for e in merged:
        for s in e["sources"]:
            src_counts[s["source"]] += 1
            if s.get("headline"):
                src_headlines[s["source"]] += 1
    print("Per-source coverage (post-merge):")
    for k in sorted(src_counts.keys(), key=lambda x: -src_counts[x]):
        h = src_headlines[k]
        print(f"  {k:12s} entries={src_counts[k]:5d}  with-headline={h:5d}")
    # Display-headline coverage
    display_h = sum(1 for e in merged if e.get("headline"))
    print(f"\nEntries with display headline: {display_h} / {len(merged)} ({display_h * 100 // len(merged)}%)")

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": 2,
        "fetched_at": date.today().isoformat(),
        "source_root": "kenrick95/itazuraneko local mirror; HJG link-outs rewritten to Wayback",
        "entries": merged,
    }
    OUT_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
    print(f"\nWrote {OUT_PATH}  ({OUT_PATH.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
