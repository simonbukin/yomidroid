#!/usr/bin/env python3
"""
Scrape grammar definitions from JLPTSensei.

This script fetches grammar points and their short English definitions
from jlptsensei.com for all JLPT levels (N5-N1).

Output: tools/data/jlptsensei-grammar.json
"""

import json
import re
import subprocess
import time
from pathlib import Path


COOKIE_FILE = '/tmp/jlptsensei_cookies.txt'

def fetch_page(url, retries=3):
    """Fetch a page using curl with session cookies and retry logic."""
    print(f"  Fetching: {url}")
    for attempt in range(retries):
        result = subprocess.run(
            [
                'curl', '-s',
                '-A', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36',
                '-c', COOKIE_FILE,  # Save cookies
                '-b', COOKIE_FILE,  # Send cookies
                '--compressed',      # Accept gzip
                url
            ],
            capture_output=True, text=True
        )
        html = result.stdout
        # Check if we got valid data (has grammar rows)
        if '<tr class=jl-row>' in html:
            time.sleep(2.0)  # Be polite - 2 seconds between requests
            return html
        print(f"    Retry {attempt + 1}/{retries}...")
        time.sleep(3.0)  # Wait longer before retry
    return result.stdout


def parse_grammar_table(html):
    """Parse grammar points from a JLPTSensei grammar list page using regex."""
    entries = []

    # HTML doesn't have closing </tr> tags (HTML5 allows this)
    # So we split by <tr class=jl-row> and process each segment

    # Split HTML by jl-row markers
    parts = re.split(r'<tr class=jl-row>', html)

    for part in parts[1:]:  # Skip first part (before first row)
        # Each part contains one row's content until the next <tr> or end
        # Extract Japanese pattern from jl-link jp anchor
        jp_match = re.search(r'class="jl-link jp"[^>]*>([^<]+)</a>', part)
        # Extract meaning from jl-td-gm cell
        meaning_match = re.search(r'class="jl-td-gm[^"]*"[^>]*>([^<]+)', part)

        if jp_match and meaning_match:
            pattern = jp_match.group(1).strip()
            meaning = meaning_match.group(1).strip()

            if pattern and meaning:
                entries.append({
                    "pattern": pattern,
                    "meaning": meaning
                })

    return entries


def get_pagination_urls(html, base_url):
    """Extract all pagination URLs from the page (like the JS scraper does)."""
    urls = [base_url]  # Include the first page

    # Find all page-numbers links
    # HTML format: <a class=page-numbers href=https://...> (no quotes!)
    # or: <a class="page-numbers" href=https://...>
    matches = re.findall(r'page-numbers["\s][^>]*href=([^\s>]+)', html)

    for url in matches:
        # Clean up URL (remove trailing > or quotes)
        url = url.strip('"\'>')
        if url not in urls and '/page/' in url:
            urls.append(url)

    # Sort by page number to ensure order
    def get_page_num(url):
        match = re.search(r'/page/(\d+)/', url)
        return int(match.group(1)) if match else 1

    urls.sort(key=get_page_num)
    return urls


def scrape_level(level):
    """Scrape all grammar points for a JLPT level."""
    print(f"\nScraping {level.upper()}...")

    all_entries = []
    base_url = f"https://jlptsensei.com/jlpt-{level}-grammar-list/"

    # Get first page to find all pagination URLs
    html = fetch_page(base_url)
    page_urls = get_pagination_urls(html, base_url)
    print(f"  Found {len(page_urls)} pages")

    # Process first page
    entries = parse_grammar_table(html)
    print(f"    Page 1: {len(entries)} entries")
    all_entries.extend(entries)

    # Fetch remaining pages using actual URLs from pagination
    for i, page_url in enumerate(page_urls[1:], 2):
        html = fetch_page(page_url)
        entries = parse_grammar_table(html)
        print(f"    Page {i}: {len(entries)} entries")
        all_entries.extend(entries)

    print(f"  Total: {len(all_entries)} grammar points")
    return all_entries


def main():
    script_dir = Path(__file__).parent
    output_dir = script_dir / 'data'
    output_dir.mkdir(exist_ok=True)
    output_path = output_dir / 'jlptsensei-grammar.json'

    all_grammar = {}
    total = 0

    levels = ["n5", "n4", "n3", "n2", "n1"]
    for level in levels:
        entries = scrape_level(level)
        all_grammar[level.upper()] = entries
        total += len(entries)

    # Build output structure
    output = {
        "source": "JLPTSensei",
        "url": "https://jlptsensei.com",
        "scraped_at": time.strftime("%Y-%m-%d"),
        "grammar": all_grammar
    }

    # Write output
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f"\n✓ Saved {total} grammar points to {output_path}")

    # Print summary
    print("\nSummary:")
    for level in levels:
        count = len(all_grammar[level.upper()])
        print(f"  {level.upper()}: {count} points")


if __name__ == '__main__':
    main()
