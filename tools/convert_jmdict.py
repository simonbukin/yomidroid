#!/usr/bin/env python3
"""
Convert JMDict XML to SQLite database for VNDict app.

Usage:
    python convert_jmdict.py JMdict_e.xml output.db

Downloads JMDict from: https://www.edrdg.org/jmdict/edict_doc.html
Extract the .gz file first.
"""

import sys
import sqlite3
import json
import xml.etree.ElementTree as ET
from pathlib import Path


def create_database(db_path: str):
    """Create the SQLite database with proper schema."""
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    cursor.execute('''
        CREATE TABLE IF NOT EXISTS terms (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            expression TEXT NOT NULL,
            reading TEXT NOT NULL,
            glossary TEXT NOT NULL,
            pos TEXT,
            score INTEGER DEFAULT 0,
            sequence INTEGER
        )
    ''')

    cursor.execute('CREATE INDEX IF NOT EXISTS idx_expression ON terms(expression)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_reading ON terms(reading)')

    conn.commit()
    return conn


def parse_jmdict(xml_path: str, conn: sqlite3.Connection):
    """Parse JMDict XML and insert entries into database."""
    cursor = conn.cursor()

    print(f"Parsing {xml_path}...")

    # Parse iteratively to handle large files
    context = ET.iterparse(xml_path, events=('end',))

    count = 0
    batch = []
    batch_size = 1000

    for event, elem in context:
        if elem.tag == 'entry':
            # Extract entry data
            sequence = None
            seq_elem = elem.find('ent_seq')
            if seq_elem is not None:
                sequence = int(seq_elem.text)

            # Get kanji elements (expressions)
            expressions = []
            for keb in elem.findall('.//keb'):
                if keb.text:
                    expressions.append(keb.text)

            # Get reading elements
            readings = []
            for reb in elem.findall('.//reb'):
                if reb.text:
                    readings.append(reb.text)

            # If no kanji, use reading as expression
            if not expressions:
                expressions = readings.copy()

            # Get senses (definitions)
            glossary_list = []
            pos_list = []

            for sense in elem.findall('sense'):
                # Get parts of speech
                for pos in sense.findall('pos'):
                    if pos.text:
                        pos_list.append(pos.text)

                # Get glosses (definitions)
                for gloss in sense.findall('gloss'):
                    if gloss.text:
                        glossary_list.append(gloss.text)

            if not glossary_list:
                elem.clear()
                continue

            # Create entries for each expression/reading combination
            glossary_json = json.dumps(glossary_list, ensure_ascii=False)
            pos_json = json.dumps(list(set(pos_list)), ensure_ascii=False)

            for expr in expressions:
                for reading in readings:
                    batch.append((
                        expr,
                        reading,
                        glossary_json,
                        pos_json,
                        0,  # score (can be updated with frequency data)
                        sequence
                    ))

                    count += 1

            # Insert batch
            if len(batch) >= batch_size:
                cursor.executemany('''
                    INSERT INTO terms (expression, reading, glossary, pos, score, sequence)
                    VALUES (?, ?, ?, ?, ?, ?)
                ''', batch)
                conn.commit()
                batch = []
                print(f"  Processed {count} entries...")

            # Clear element to save memory
            elem.clear()

    # Insert remaining batch
    if batch:
        cursor.executemany('''
            INSERT INTO terms (expression, reading, glossary, pos, score, sequence)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', batch)
        conn.commit()

    print(f"Total entries: {count}")
    return count


def add_frequency_data(conn: sqlite3.Connection, freq_file: str):
    """
    Optional: Add frequency scores from a frequency list.
    File format: word<tab>frequency
    """
    if not Path(freq_file).exists():
        print(f"Frequency file {freq_file} not found, skipping...")
        return

    cursor = conn.cursor()
    print(f"Adding frequency data from {freq_file}...")

    with open(freq_file, 'r', encoding='utf-8') as f:
        for line in f:
            parts = line.strip().split('\t')
            if len(parts) >= 2:
                word = parts[0]
                try:
                    freq = int(parts[1])
                    cursor.execute('''
                        UPDATE terms SET score = ? WHERE expression = ? OR reading = ?
                    ''', (freq, word, word))
                except ValueError:
                    pass

    conn.commit()
    print("Frequency data added.")


def main():
    if len(sys.argv) < 3:
        print("Usage: python convert_jmdict.py <JMdict_e.xml> <output.db> [frequency.txt]")
        print()
        print("Download JMdict from: https://www.edrdg.org/wiki/index.php/JMdict-EDICT_Dictionary_Project")
        sys.exit(1)

    xml_path = sys.argv[1]
    db_path = sys.argv[2]
    freq_path = sys.argv[3] if len(sys.argv) > 3 else None

    if not Path(xml_path).exists():
        print(f"Error: {xml_path} not found")
        sys.exit(1)

    print(f"Converting {xml_path} to {db_path}...")

    conn = create_database(db_path)
    count = parse_jmdict(xml_path, conn)

    if freq_path:
        add_frequency_data(conn, freq_path)

    # Get final stats
    cursor = conn.cursor()
    cursor.execute('SELECT COUNT(*) FROM terms')
    total = cursor.fetchone()[0]

    cursor.execute('SELECT COUNT(DISTINCT expression) FROM terms')
    unique_expr = cursor.fetchone()[0]

    conn.close()

    print()
    print(f"Done! Database created at: {db_path}")
    print(f"  Total entries: {total}")
    print(f"  Unique expressions: {unique_expr}")
    print()
    print("To use with VNDict:")
    print(f"  1. Copy {db_path} to your Android device")
    print("  2. Or place in app/src/main/assets/ before building")


if __name__ == '__main__':
    main()
