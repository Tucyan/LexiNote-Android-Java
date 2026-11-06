#!/usr/bin/env python3
"""Build the pinned, offline ECDICT subset; no network access is performed."""
import argparse
import csv
import hashlib
import os
import sqlite3

SOURCE_SHA256 = 'd1ecbdf9ee2b920afb6f430ca6b6bb9f559588cf0dff86e04bd255f9390843cc'
COMMIT = '39cfb9672c0906b63569e0453c2e6abfb9ab182a'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source')
    parser.add_argument('output')
    args = parser.parse_args()
    with open(args.source, 'rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != SOURCE_SHA256:
        raise SystemExit('Unexpected ECDICT source SHA-256: ' + digest)
    if os.path.exists(args.output):
        os.unlink(args.output)
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    db = sqlite3.connect(args.output)
    db.executescript('PRAGMA page_size=4096; PRAGMA journal_mode=OFF; CREATE TABLE entry (key TEXT PRIMARY KEY, word TEXT NOT NULL, phonetic TEXT NOT NULL, definition TEXT NOT NULL, translation TEXT NOT NULL) WITHOUT ROWID; CREATE TABLE variant (key TEXT PRIMARY KEY, headword TEXT NOT NULL) WITHOUT ROWID;')
    variants = {}
    entries = set()
    with open(args.source, encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream):
            word = row['word'].strip()
            key = word.lower()
            ranked = row['oxford'] == '1' or row['collins'] not in ('', '0') or bool(row['tag']) or any(row[k].isdigit() and 0 < int(row[k]) <= 50000 for k in ('bnc', 'frq'))
            phrase = ' ' in word and len(word.split()) <= 3
            if not word or not (ranked or phrase) or not (row['definition'].strip() or row['translation'].strip()):
                continue
            db.execute('INSERT OR IGNORE INTO entry VALUES (?,?,?,?,?)', (key, word, row['phonetic'], row['definition'], row['translation']))
            entries.add(key)
            for item in row['exchange'].split('/'):
                kind, sep, form = item.partition(':')
                if not sep or kind not in ('p', 'd', 'i', '3', 'r', 't', 's'):
                    continue
                for value in form.split(','):
                    value = value.strip().lower()
                    if value and value != key:
                        variants.setdefault(value, set()).add(key)
    # Ambiguous inflections do not silently select an arbitrary lemma.
    db.executemany('INSERT INTO variant VALUES (?,?)', ((form, next(iter(heads))) for form, heads in sorted(variants.items()) if len(heads) == 1))
    db.execute('PRAGMA user_version=1')
    db.commit()
    db.execute('VACUUM')
    print('entries:', len(entries), 'variants:', db.execute('SELECT count(*) FROM variant').fetchone()[0])
    db.close()
    print('bytes:', os.path.getsize(args.output), 'source SHA-256:', digest)

if __name__ == '__main__':
    main()
