#!/usr/bin/env python3
"""
Convert dictionary sources to unified SQLite for VNDict.

Supports:
- Jitendex (Yomitan ZIP format)
- JMnedict (XML format from EDRDG)
- VN Frequency data (Yomitan frequency ZIP or text file)

Usage:
    python convert_dictionaries.py \
        --jitendex path/to/jitendex.zip \
        --jmnedict path/to/JMnedict.xml \
        --frequency path/to/vn_frequency.zip \
        --output dictionary.db
"""

import argparse
import sqlite3
import json
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List, Optional, Tuple


def extract_text_recursive(content) -> str:
    """
    Recursively extract plain text from nested content.
    """
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = [extract_text_recursive(item) for item in content]
        return " ".join(p for p in parts if p)
    if isinstance(content, dict):
        # Skip images and links
        tag = content.get('tag')
        if tag in ('img', 'a'):
            return ''
        # Handle ruby text - extract base text only, skip rt/rp
        if tag == 'ruby':
            inner = content.get('content')
            if isinstance(inner, list):
                # Ruby content is [base, rt, rp, ...] - just get strings (base text)
                return "".join(extract_text_recursive(item) for item in inner
                              if isinstance(item, str))
            return extract_text_recursive(inner)
        if tag in ('rt', 'rp'):
            return ''  # Skip ruby annotations
        # Recurse into content
        return extract_text_recursive(content.get('content'))
    return ""


def extract_glossary_from_structured(content) -> List[str]:
    """
    Extract glossary definitions from Jitendex structured content.
    Only extracts from elements with data.content="glossary".
    Returns list of definition strings.
    """
    glosses = []

    def find_glossaries(node):
        if node is None:
            return
        if isinstance(node, str):
            return
        if isinstance(node, list):
            for item in node:
                find_glossaries(item)
            return
        if isinstance(node, dict):
            # Check if this is a glossary container
            data = node.get('data', {})
            if isinstance(data, dict) and data.get('content') == 'glossary':
                # Extract all li items from this glossary
                inner = node.get('content')
                if isinstance(inner, dict) and inner.get('tag') == 'li':
                    text = extract_text_recursive(inner.get('content'))
                    if text and text.strip():
                        glosses.append(text.strip())
                elif isinstance(inner, list):
                    for item in inner:
                        if isinstance(item, dict) and item.get('tag') == 'li':
                            text = extract_text_recursive(item.get('content'))
                            if text and text.strip():
                                glosses.append(text.strip())
            else:
                # Recurse into content
                find_glossaries(node.get('content'))

    find_glossaries(content)
    return glosses


def extract_text_from_content(content) -> str:
    """
    Extract text from Yomitan structured content.
    For Jitendex, extracts only glossary definitions.
    Falls back to full text extraction for simple content.
    """
    if content is None:
        return ""

    # String content -> return directly
    if isinstance(content, str):
        return content

    # Object with structured content
    if isinstance(content, dict):
        content_type = content.get('type')
        if content_type == 'structured-content':
            # Try to extract just glossary definitions from Jitendex format
            glosses = extract_glossary_from_structured(content.get('content'))
            if glosses:
                return "; ".join(glosses)
            # Fallback to full extraction
            return extract_text_recursive(content.get('content'))
        if content_type == 'text':
            return content.get('text', '')
        if content_type == 'image':
            return ''

    # Array -> recursively process
    if isinstance(content, list):
        parts = [extract_text_from_content(item) for item in content]
        return " ".join(p for p in parts if p)

    return extract_text_recursive(content)


def create_schema(conn: sqlite3.Connection) -> None:
    """Create unified schema with source/frequency support."""
    cursor = conn.cursor()
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS terms (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            expression TEXT NOT NULL,
            reading TEXT NOT NULL,
            glossary TEXT NOT NULL,
            pos TEXT,
            score INTEGER DEFAULT 0,
            sequence INTEGER,
            source TEXT NOT NULL DEFAULT 'jitendex',
            name_type TEXT,
            frequency_rank INTEGER
        )
    ''')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_expression ON terms(expression)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_reading ON terms(reading)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_source ON terms(source)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_frequency ON terms(frequency_rank)')
    conn.commit()


def parse_yomitan_zip(zip_path: str) -> List[dict]:
    """Parse Yomitan dictionary format (ZIP with term_bank_*.json)."""
    entries = []
    with zipfile.ZipFile(zip_path, 'r') as zf:
        for name in zf.namelist():
            if name.startswith('term_bank_') and name.endswith('.json'):
                print(f"    Processing {name}...")
                with zf.open(name) as f:
                    data = json.load(f)
                    for entry in data:
                        # Yomitan term format:
                        # [expression, reading, definitionTags, rules, score, definitions, sequence, termTags]
                        if len(entry) >= 6:
                            expression = entry[0]
                            reading = entry[1] if entry[1] else expression
                            tags = entry[2] if len(entry) > 2 and entry[2] else ''
                            score = entry[4] if len(entry) > 4 and isinstance(entry[4], int) else 0
                            definitions = entry[5]
                            sequence = entry[6] if len(entry) > 6 and isinstance(entry[6], int) else 0

                            # Handle definitions - can be list of strings or structured content
                            glossary = []
                            if isinstance(definitions, list):
                                for d in definitions:
                                    text = extract_text_from_content(d)
                                    if text and text.strip():
                                        glossary.append(text.strip())
                            elif isinstance(definitions, str):
                                glossary = [definitions]

                            if glossary:
                                entries.append({
                                    'expression': expression,
                                    'reading': reading,
                                    'pos': tags,
                                    'score': score,
                                    'glossary': glossary,
                                    'sequence': sequence
                                })
    return entries


def parse_yomitan_frequency_zip(zip_path: str) -> Dict[str, int]:
    """Parse Yomitan frequency dictionary ZIP and return word->rank mapping."""
    # First pass: collect all frequency values
    freq_counts = {}

    with zipfile.ZipFile(zip_path, 'r') as zf:
        for name in zf.namelist():
            if name.startswith('term_meta_bank_') and name.endswith('.json'):
                print(f"    Processing {name}...")
                with zf.open(name) as f:
                    data = json.load(f)
                    for entry in data:
                        # Yomitan frequency format: [term, "freq", {frequency data}]
                        if len(entry) >= 3 and entry[1] == 'freq':
                            term = entry[0]
                            freq_data = entry[2]

                            # Frequency can be: number, {value: number}, {frequency: number}, etc.
                            freq_value = None
                            if isinstance(freq_data, (int, float)):
                                freq_value = int(freq_data)
                            elif isinstance(freq_data, dict):
                                if 'value' in freq_data:
                                    freq_value = int(freq_data['value'])
                                elif 'frequency' in freq_data:
                                    freq_value = int(freq_data['frequency'])
                                elif 'displayValue' in freq_data:
                                    try:
                                        freq_value = int(freq_data['displayValue'])
                                    except (ValueError, TypeError):
                                        pass

                            if freq_value is not None and term not in freq_counts:
                                freq_counts[term] = freq_value

    # Convert occurrence counts to ranks (higher count = lower rank = more common)
    print(f"    Converting {len(freq_counts)} frequency entries to ranks...")
    sorted_terms = sorted(freq_counts.items(), key=lambda x: x[1], reverse=True)
    freq_map = {term: rank + 1 for rank, (term, _) in enumerate(sorted_terms)}

    return freq_map


def parse_frequency_text(txt_path: str) -> Dict[str, int]:
    """Parse simple text frequency file (word per line or word<tab>freq)."""
    freq_map = {}
    rank = 1

    with open(txt_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue

            parts = line.split('\t')
            word = parts[0]

            if word and word not in freq_map:
                freq_map[word] = rank
                rank += 1

    return freq_map


def load_frequency_data(freq_path: str) -> Dict[str, int]:
    """Load frequency data from ZIP or text file."""
    path = Path(freq_path)

    if not path.exists():
        print(f"Warning: Frequency file not found: {freq_path}")
        return {}

    if path.suffix.lower() == '.zip':
        return parse_yomitan_frequency_zip(freq_path)
    else:
        return parse_frequency_text(freq_path)


# JMnedict name type mappings - handles resolved entity text
NAME_TYPE_MAPPING = {
    # Short entity names (in case they're not resolved)
    'surname': 'surname',
    'place': 'place',
    'unclass': 'person',
    'given': 'given',
    'fem': 'given',
    'masc': 'given',
    'person': 'person',
    'company': 'company',
    'product': 'product',
    'work': 'work',
    'station': 'place',
    'organization': 'company',
    'org': 'company',
    'char': 'person',
    'group': 'company',
    # Resolved entity text (full descriptions)
    'family or surname': 'surname',
    'place name': 'place',
    'unclassified name': 'person',
    'given name or forename, gender not specified': 'given',
    'female given name or forename': 'given',
    'male given name or forename': 'given',
    'full name of a particular person': 'person',
    'company name': 'company',
    'product name': 'product',
    'work of art, literature, music, etc. name': 'work',
    'railway station name': 'place',
    'organization name': 'company',
    'character name': 'person',
    'creature name': 'other',
    'deity name': 'other',
    'event name': 'other',
    'fictional name': 'other',
    'legend name': 'other',
    'mythology name': 'other',
    'object name': 'other',
    'other name': 'other',
    'religion name': 'other',
    'service name': 'other',
    'ship name': 'other',
    'group or band name': 'company',
}


def parse_jmnedict_xml(xml_path: str) -> List[dict]:
    """Parse JMnedict XML format."""
    entries = []

    print(f"    Parsing XML (this may take a while)...")
    context = ET.iterparse(xml_path, events=('end',))

    count = 0
    for event, elem in context:
        if elem.tag == 'entry':
            # Extract kanji expressions
            expressions = []
            for keb in elem.findall('.//keb'):
                if keb.text:
                    expressions.append(keb.text)

            # Extract readings
            readings = []
            for reb in elem.findall('.//reb'):
                if reb.text:
                    readings.append(reb.text)

            # If no kanji, use reading as expression
            if not expressions:
                expressions = readings.copy()

            # Extract translations and name types
            glossary = []
            name_type = None

            for trans in elem.findall('.//trans'):
                # Get name type from trans element
                for name_type_elem in trans.findall('name_type'):
                    if name_type_elem.text:
                        raw_type = name_type_elem.text.strip().lower()
                        name_type = NAME_TYPE_MAPPING.get(raw_type, 'other')

                # Get translations
                for tran_det in trans.findall('trans_det'):
                    if tran_det.text:
                        glossary.append(tran_det.text)

            # Create entries for each expression-reading combination
            if glossary:
                for expr in expressions:
                    for reading in readings:
                        entries.append({
                            'expression': expr,
                            'reading': reading,
                            'glossary': glossary,
                            'name_type': name_type or 'other',
                            'pos': 'name',
                            'score': 0,
                            'sequence': 0
                        })

            count += 1
            if count % 50000 == 0:
                print(f"    Processed {count} entries...")

            elem.clear()

    return entries


def main():
    parser = argparse.ArgumentParser(
        description='Convert dictionaries for VNDict',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
    # Full build with all sources
    python convert_dictionaries.py \\
        --jitendex jitendex-eng.zip \\
        --jmnedict JMnedict.xml \\
        --frequency JPDB_freq.zip \\
        --output dictionary.db

    # Just Jitendex with frequency
    python convert_dictionaries.py \\
        --jitendex jitendex-eng.zip \\
        --frequency JPDB_freq.zip \\
        --output dictionary.db
        """
    )
    parser.add_argument('--jitendex', help='Jitendex Yomitan ZIP file')
    parser.add_argument('--jmnedict', help='JMnedict XML file')
    parser.add_argument('--frequency', help='Frequency data (Yomitan ZIP or text file)')
    parser.add_argument('--output', required=True, help='Output SQLite database')
    args = parser.parse_args()

    if not args.jitendex and not args.jmnedict:
        parser.error("At least one of --jitendex or --jmnedict is required")

    # Remove existing database
    output_path = Path(args.output)
    if output_path.exists():
        output_path.unlink()

    conn = sqlite3.connect(args.output)
    create_schema(conn)
    cursor = conn.cursor()

    # Load frequency data first
    freq_map: Dict[str, int] = {}
    if args.frequency:
        print(f"Loading frequency data from {args.frequency}...")
        freq_map = load_frequency_data(args.frequency)
        print(f"  Loaded {len(freq_map)} frequency entries")

    # Process Jitendex
    jitendex_count = 0
    if args.jitendex:
        print(f"\nProcessing Jitendex from {args.jitendex}...")
        entries = parse_yomitan_zip(args.jitendex)

        batch = []
        for entry in entries:
            freq_rank = freq_map.get(entry['expression'])
            batch.append((
                entry['expression'],
                entry['reading'],
                json.dumps(entry['glossary'], ensure_ascii=False),
                entry['pos'],
                entry['score'],
                entry['sequence'],
                'jitendex',
                None,  # name_type
                freq_rank
            ))

            if len(batch) >= 10000:
                cursor.executemany('''
                    INSERT INTO terms (expression, reading, glossary, pos, score, sequence, source, name_type, frequency_rank)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ''', batch)
                conn.commit()
                jitendex_count += len(batch)
                print(f"  Inserted {jitendex_count} entries...")
                batch = []

        if batch:
            cursor.executemany('''
                INSERT INTO terms (expression, reading, glossary, pos, score, sequence, source, name_type, frequency_rank)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', batch)
            conn.commit()
            jitendex_count += len(batch)

        print(f"  Total Jitendex entries: {jitendex_count}")

    # Process JMnedict
    jmnedict_count = 0
    if args.jmnedict:
        print(f"\nProcessing JMnedict from {args.jmnedict}...")
        entries = parse_jmnedict_xml(args.jmnedict)

        batch = []
        for entry in entries:
            batch.append((
                entry['expression'],
                entry['reading'],
                json.dumps(entry['glossary'], ensure_ascii=False),
                entry['pos'],
                0,  # score
                0,  # sequence
                'jmnedict',
                entry['name_type'],
                None  # No frequency for names
            ))

            if len(batch) >= 10000:
                cursor.executemany('''
                    INSERT INTO terms (expression, reading, glossary, pos, score, sequence, source, name_type, frequency_rank)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ''', batch)
                conn.commit()
                jmnedict_count += len(batch)
                print(f"  Inserted {jmnedict_count} entries...")
                batch = []

        if batch:
            cursor.executemany('''
                INSERT INTO terms (expression, reading, glossary, pos, score, sequence, source, name_type, frequency_rank)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', batch)
            conn.commit()
            jmnedict_count += len(batch)

        print(f"  Total JMnedict entries: {jmnedict_count}")

    # Print final stats
    cursor.execute('SELECT COUNT(*) FROM terms')
    total = cursor.fetchone()[0]
    cursor.execute('SELECT COUNT(*) FROM terms WHERE frequency_rank IS NOT NULL')
    freq_count = cursor.fetchone()[0]

    print(f"\n{'='*50}")
    print(f"Database created: {args.output}")
    print(f"  Total entries: {total:,}")
    if jitendex_count:
        print(f"  Jitendex: {jitendex_count:,}")
    if jmnedict_count:
        print(f"  JMnedict: {jmnedict_count:,}")
    print(f"  With frequency data: {freq_count:,}")

    # Get file size
    conn.close()
    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"  File size: {size_mb:.1f} MB")


if __name__ == '__main__':
    main()
