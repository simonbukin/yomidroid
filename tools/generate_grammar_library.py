#!/usr/bin/env python3
"""
Generate unified grammar library from multiple sources.

Sources:
- GameGengo: Video timestamps (N5-N2)
- JLPTSensei: Short English definitions (N5-N1)
- DOJG: Reference URLs

All sources are merged into a unified list. Each grammar pattern can have
data from multiple sources, tracked in a 'sources' list.
"""

import json
import re
import urllib.parse
from pathlib import Path

# GameGengo video IDs
GAMEGENGO_VIDEOS = {
    "N5": {"id": "_ojVS-KgDEg", "title": "Complete JLPT N5 Grammar Video(Game) Textbook"},
    "N4": {"id": "M0yEOIEuaDg", "title": "Complete JLPT N4 Grammar Video(Game) Textbook"},
    "N3": {"id": "VrscRD5y2gk", "title": "Complete JLPT N3 Grammar Video(Game) Textbook"},
    "N2": {"id": "aIfxj0ZQ688", "title": "Complete JLPT N2 Grammar Video(Game) Textbook"},
}

# GameGengo timestamps - these ARE the grammar points
# Format: "MM:SS - pattern" or "H:MM:SS - pattern"
GAMEGENGO_N5 = """
00:56 - ちゃいけない
02:20 - じゃいけない
03:25 - じゃだめ
04:16 - です
05:36 - だ
06:46 - だから
07:42 - だけ
08:42 - でしょう
09:46 - だろう
10:36 - で
12:06 - でも
13:02 - どんな
13:37 - どうして
14:14 - どうやって
14:53 - が
17:18 - があります
18:23 - がいます
18:58 - がほしい
19:43 - ほうがいい
20:53 - い-adjective
22:47 - 一番
23:56 - 一緒に
24:49 - いつも
25:43 - ではない
27:10 - じゃない
28:04 - か
28:45 - か～か
29:54 - から
31:09 - 方
32:40 - けど
33:42 - けれども
34:40 - くらい・ぐらい
35:56 - まだ
37:01 - まだ～ていません
37:55 - まで
39:10 - 前に
40:02 - ませんか
41:03 - ましょう
41:37 - ましょうか
42:17 - も
43:39 - もう
45:20 - な-adjective
48:10 - な
49:05 - なあ
50:44 - ないで
52:38 - ないでください
53:53 - ないといけない
55:22 - なくてはいけない
56:52 - なくてはならない
57:53 - なくちゃ
58:43 - なくてもいい
59:59 - なる
1:02:12 - のです
1:04:35 - んです
1:05:26 - ね
1:06:40 - に
1:09:35 - に・へ
1:10:46 - にいく
1:13:33 - にする
1:14:57 - の
1:17:28 - のが下手
1:18:29 - のが上手
1:19:39 - のが好き
1:20:43 - ので
1:22:28 - お
1:25:01 - ご
1:28:52 - を
1:30:04 - をください
1:31:09 - しかし
1:32:51 - それから
1:34:09 - そして
1:35:37 - すぎる
1:37:47 - たことがある
1:39:06 - たことがない
1:40:11 - たい
1:41:18 - たり～たり
1:43:45 - てある
1:45:19 - ている
1:46:29 - てから
1:47:44 - てください
1:48:54 - てはいけない
1:50:01 - てもいい
1:51:57 - と
1:54:32 - とき
1:56:12 - とても
1:57:29 - つもり
1:58:59 - は
2:00:54 - は～より...です
2:02:00 - はどうですか
2:02:50 - や
2:04:46 - よ
2:05:44 - より～ほうが
"""

GAMEGENGO_N4 = """
0:57 - 間
2:22 - 間に
4:04 - あまり～ない
5:02 - 後で
5:59 - ば
9:31 - 場合は
12:00 - ばかり
13:44 - だけで
15:38 - 出す
17:38 - でございます
19:17 - でも
20:29 - ではないか
21:52 - が必要
24:40 - がする
26:24 - がる・がっている
28:22 - がり
30:42 - ございます
32:12 - 始める
34:33 - はずだ
35:45 - はずがない
37:00 - 必要がある
39:20 - 意向形
41:48 - いらっしゃる
43:24 - いたします
46:40 - じゃないか
48:02 - かどうか
50:05 - かしら
51:30 - かい
53:06 - かもしれない
55:04 - かな
55:57 - で・から作る
57:15 - きっと
58:34 - 頃
1:01:06 - こと
1:03:05 - ことがある
1:04:41 - ことができる
1:06:22 - ことになる
1:09:16 - ことにする
1:11:00 - くする
1:13:04 - 急に
1:13:56 - までに
1:15:22 - まま
1:19:00 - または
1:20:48 - みたいだ
1:22:06 - みたいな
1:23:35 - みたいに
1:26:15 - も
1:28:48 - な
1:30:18 - など
1:31:46 - ながら
1:34:50 - なかなか～ない
1:36:16 - なければいけない
1:38:18 - なければならない
1:39:46 - なら
1:41:25 - なさる
1:44:51 - に気がつく
1:46:50 - にみえる
1:48:24 - にくい
1:50:13 - の中で
1:51:35 - のに
1:54:41 - のは～だ
1:56:58 - お～ください
1:59:26 - お～になる
2:01:53 - おきに
2:03:38 - おる
2:05:24 - 終わる
2:06:21 - られる
2:08:56 - らしい
2:11:46 - さ
2:13:06 - さっき
2:14:37 - させる
2:17:51 - させられる
2:19:35 - させてください
2:21:07 - さすが
2:23:11 - し
2:25:21 - しか～ない
2:27:12 - そんなに
2:28:19 - それでも
2:30:18 - それに
2:31:59 - そうだ
2:37:21 - そうに・そうな
2:40:12 - たばかり
2:41:35 - たところ
2:43:11 - 他動詞・自動詞
2:46:48 - たがる
2:48:40 - たら
2:51:16 - たらどう
2:52:45 - たらいいですか
2:54:35 - て・で
2:56:34 - てあげる
2:57:59 - てほしい
2:59:31 - ていく
3:00:53 - ていた
3:02:01 - ていただけませんか
3:03:24 - てくれる
3:05:16 - てくる
3:07:37 - てみる
3:09:06 - てもらう
3:11:38 - ておく
3:13:24 - てしまう・ちゃう
3:14:59 - てすみません
3:16:00 - てやる
3:17:41 - てよかった
3:18:45 - ているところ
3:20:27 - ても・でも
3:21:55 - と
3:23:31 - と言ってもいい
3:25:05 - という
3:27:23 - と言われている
3:29:14 - と聞いた
3:30:20 - と思う
3:32:00 - とか～とか
3:33:47 - ところ
3:35:37 - 続ける
3:36:55 - って
3:38:47 - 受身形
3:43:46 - は～が…は
3:45:46 - やすい
3:47:04 - やっと
3:48:13 - より
3:49:50 - 予定だ
3:51:02 - ようだ
3:53:54 - ように・ような
3:56:47 - ようになる
3:58:43 - ようにする
4:00:01 - ようと思う
4:01:36 - ぜひ
4:02:34 - 全然～ない
4:04:15 - ぜんぜん
4:06:16 - づらい
"""

GAMEGENGO_N3 = """
01:00 - 上げる
04:00 - あまり
09:11 - ばいい
10:21 - ばよかった
11:28 - ば～ほど
13:36 - ば～のに
15:39 - ばかりで
17:52 - ばかりでなく
19:31 - べきだ
21:38 - べきではない
22:52 - 別に～ない
24:25 - ぶりに
26:42 - 中
29:34 - だけ
31:50 - だけでなく
35:43 - だけど
37:59 - だらけ
44:10 - どんなに～ても
46:23 - どうしても
48:16 - ふりをする
50:17 - ふと
51:48 - がち
53:37 - がたい
56:09 - 気味
57:57 - ごとに
1:00:45 - ほど
1:03:36 - ほど～ない
1:07:32 - 一度に
1:08:44 - 一体
1:11:04 - 一方だ
1:15:44 - か何か
1:20:35 - かける
1:23:02 - 結果
1:24:19 - 代わりに
1:28:14 - 結局
1:35:15 - っけ
1:39:08 - こそ
1:47:57 - ことはない
1:54:06 - くせに
1:56:59 - まるで
2:00:49 - めったに～ない
2:10:23 - 向け
2:11:38 - 向き
2:20:11 - なかなか
2:26:55 - ないことはない
2:38:35 - に違いない
2:42:04 - から～にかけて
2:44:43 - に関して
2:46:28 - にかわって
2:48:12 - に比べて
2:51:56 - において
2:54:21 - にしては
2:55:43 - にしても
2:57:29 - にたいして
3:02:14 - にとって
3:04:53 - について
3:08:41 - につれて
3:10:23 - によると
3:12:42 - によって
3:28:56 - おかげで
3:35:07 - っぱなし
3:36:24 - っぽい
3:38:57 - さえ
3:42:33 - さえ～ば
3:47:07 - 最中に
3:51:55 - せいで
3:58:39 - しかない
4:06:52 - そうもない
4:14:55 - たび
4:17:15 - ために
4:22:09 - たとたん
4:27:27 - たとえ～ても
4:34:16 - てばかりいる
4:35:32 - てはじめて
4:50:27 - てもかまわない
4:58:36 - と言えば
5:04:18 - ということは
5:08:51 - というと
5:10:11 - というより
5:14:43 - とともに
5:23:31 - 途中
5:26:04 - ところで
5:27:12 - とおり
5:35:27 - として
5:38:35 - とは限らない
5:40:18 - ついに
5:43:54 - ついでに
5:46:07 - つまり
5:51:39 - うちに
5:55:06 - 上で
6:03:33 - わけだ
6:06:30 - わけではない
6:08:42 - わけがない
6:11:30 - わけにはいかない
6:15:18 - よりも
6:17:10 - わりに
6:22:28 - ようがない
6:25:33 - ようとする
6:31:23 - ずに
6:33:59 - ずにはいられない
"""

GAMEGENGO_N2 = """
01:29 - あげく
03:02 - あるいは
06:49 - ばかりだ
08:39 - ばかりか
11:11 - ばかりに
13:08 - ちなみに
14:41 - ちっとも～ない
16:44 - だけあって
19:34 - だけましだ
21:41 - だけに
23:56 - だけは
26:21 - だって
30:37 - でしかない
33:01 - どころではない
37:38 - どころか
40:20 - どうやら
42:30 - どうせ
44:50 - 得る
47:33 - 得ない
50:12 - 再び
52:44 - ふうに
55:20 - がきっかけで
58:05 - げ
1:01:08 - 逆に
1:03:35 - 反面
1:05:43 - 果たして
1:09:50 - 一応
1:13:22 - 以外
1:16:27 - 以上に
1:18:34 - 以上は
1:20:56 - いきなり
1:22:25 - 一気に
1:23:54 - 一方で
1:25:53 - いわゆる
1:27:57 - いよいよ
1:30:36 - 上
1:33:35 - かのように
1:36:12 - かと思ったら
1:50:11 - かねない
1:52:27 - かねる
1:54:39 - からすると
2:02:43 - からには
2:05:16 - からして
2:11:28 - からといって
2:14:21 - っこない
2:23:40 - ことなく
2:25:22 - ことに
2:36:01 - まい
2:50:32 - もかまわず
2:54:46 - もの
2:57:51 - ものだ
3:09:09 - ものだから
3:14:23 - ものがある
3:16:35 - ものか
3:18:35 - ものなら
3:23:16 - ものの
3:26:21 - もっとも
3:39:13 - なくはない
3:48:00 - なお
3:53:50 - にあたって
3:58:37 - に限らず
4:00:56 - に限る
4:04:10 - に限って
4:06:45 - にかかわる
4:08:34 - にかかわらず
4:16:37 - にこたえて
4:19:12 - に加えて
4:28:49 - に際して
4:30:49 - に先立って
4:35:21 - にしろ～にしろ
4:37:50 - にしたら
4:39:58 - に沿って
4:44:18 - に過ぎない
4:48:46 - につけて
4:51:36 - につき
4:54:51 - にわたって
4:56:33 - にもかかわらず
5:06:33 - のみ
5:08:45 - のみならず
5:13:38 - 抜きで
5:15:21 - 抜く
5:18:12 - をきっかけに
5:20:24 - をめぐって
5:22:33 - をもとに
5:25:52 - を問わず
5:34:24 - 恐れがある
5:47:52 - 次第
5:54:12 - しかも
6:08:27 - 末に
6:21:34 - て以来
6:24:51 - てまで
6:26:32 - てならない
6:28:40 - てたまらない
6:34:20 - ていては
6:36:39 - てはいられない
6:43:53 - と同時に
6:46:06 - といった
6:48:18 - という風に
6:50:06 - ということは
6:52:43 - というものだ
6:55:11 - というものではない
7:14:54 - としても
7:16:52 - つつ
7:18:52 - つつある
7:21:16 - 上は
7:48:41 - ざるを得ない
"""


def parse_timestamp(ts_str):
    """Convert timestamp string like '1:23:45' or '2:30' to seconds."""
    parts = ts_str.strip().split(':')
    if len(parts) == 3:
        return int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
    elif len(parts) == 2:
        return int(parts[0]) * 60 + int(parts[1])
    return int(parts[0])


def parse_gamegengo_timestamps(raw_text, level):
    """Parse GameGengo timestamps into grammar entries."""
    entries = []
    for i, line in enumerate(raw_text.strip().split('\n'), 1):
        line = line.strip()
        if not line:
            continue

        # Match: "MM:SS - pattern" or "H:MM:SS - pattern"
        match = re.match(r'^(\d{1,2}:\d{2}(?::\d{2})?)\s*-\s*(.+)$', line)
        if match:
            timestamp = match.group(1)
            pattern = match.group(2).strip()
            entries.append({
                "id": f"gg-{level.lower()}-{i:03d}",
                "pattern": pattern,
                "jlptLevel": level,
                "source": "gamegengo",
                "timestamp": timestamp,
                "timestampSeconds": parse_timestamp(timestamp),
            })

    return entries


def normalize_for_matching(pattern):
    """Normalize pattern for DOJG matching."""
    p = pattern.lower()
    # Remove tildes and variations
    p = re.sub(r'[～~]', '', p)
    # Normalize separators
    p = p.replace('・', '/')
    p = p.replace('　', ' ')
    p = p.replace('...', '')
    p = p.replace('…', '')
    # Remove spaces
    p = p.replace(' ', '')
    return p


def load_jlptsensei_data(jlptsensei_path):
    """Load JLPTSensei data as list of entries with levels."""
    with open(jlptsensei_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    entries = []
    for level, level_entries in data.get('grammar', {}).items():
        for entry in level_entries:
            pattern = entry.get('pattern', '')
            meaning = entry.get('meaning', '')
            if pattern:
                entries.append({
                    'pattern': pattern,
                    'meaning': meaning,
                    'jlptLevel': level,
                    'normalized': normalize_for_matching(pattern),
                })
    return entries


def build_jlptsensei_lookup(jlptsensei_entries):
    """Build normalized pattern -> entry mapping for quick lookup."""
    sensei_map = {}
    for entry in jlptsensei_entries:
        normalized = entry['normalized']
        sensei_map[normalized] = entry
        # Also try without parenthetical readings
        pattern = entry['pattern']
        simple = re.sub(r'（[^）]+）', '', pattern)
        simple = re.sub(r'\([^)]+\)', '', simple)
        simple_norm = normalize_for_matching(simple)
        if simple_norm != normalized:
            sensei_map[simple_norm] = entry
    return sensei_map


def find_jlptsensei_match(pattern, sensei_map):
    """Find matching JLPTSensei entry for a pattern."""
    normalized = normalize_for_matching(pattern)

    # Try exact match
    if normalized in sensei_map:
        return sensei_map[normalized]

    # Try without readings in parentheses
    simple = re.sub(r'（[^）]+）', '', pattern)
    simple = re.sub(r'\([^)]+\)', '', simple)
    simple_norm = normalize_for_matching(simple)
    if simple_norm in sensei_map:
        return sensei_map[simple_norm]

    # Try each part of compound patterns (like "ちゃ/じゃ")
    for part in pattern.replace('・', '/').split('/'):
        part_norm = normalize_for_matching(part.strip())
        if part_norm in sensei_map:
            return sensei_map[part_norm]

    return None


def load_dojg_data(dojg_path):
    """Load DOJG data and build pattern -> URL mapping."""
    with open(dojg_path, 'r', encoding='utf-8') as f:
        dojg_data = json.load(f)

    # Handle both raw list and wrapped format
    if isinstance(dojg_data, dict) and 'grammarPoints' in dojg_data:
        entries = dojg_data['grammarPoints']
    else:
        entries = dojg_data

    # Build normalized pattern -> entry mapping
    dojg_map = {}
    for entry in entries:
        pattern = entry.get('pattern', '')
        url = entry.get('sourceUrl', '') or entry.get('url', '')
        if pattern and url:
            normalized = normalize_for_matching(pattern)
            dojg_map[normalized] = {
                "pattern": pattern,
                "url": url,
                "meaning": entry.get('meaning', ''),
            }
            # Also add without particles for flexibility
            simple = re.sub(r'^[はがをにで]\s*', '', normalized)
            if simple != normalized:
                dojg_map[simple] = dojg_map[normalized]

    return dojg_map


def make_jlptsensei_url(pattern):
    """Generate JLPTSensei grammar page URL from pattern."""
    # URL encode the Japanese pattern
    encoded = urllib.parse.quote(pattern, safe='')
    return f"https://jlptsensei.com/learn-japanese-grammar/{encoded}-meaning/"


def find_dojg_match(pattern, dojg_map):
    """Find matching DOJG entry for a pattern."""
    normalized = normalize_for_matching(pattern)

    # Try exact match first
    if normalized in dojg_map:
        return dojg_map[normalized]

    # Try without tilde markers
    simple = re.sub(r'[～~]', '', pattern)
    simple_norm = normalize_for_matching(simple)
    if simple_norm in dojg_map:
        return dojg_map[simple_norm]

    # Try each part of compound patterns
    for part in pattern.replace('・', '/').split('/'):
        part_norm = normalize_for_matching(part.strip())
        if part_norm in dojg_map:
            return dojg_map[part_norm]

    return None


def build_unified_grammar(gamegengo_entries, jlptsensei_entries, dojg_map):
    """
    Build unified grammar list from all sources using pattern-first merge.

    Each unique pattern gets one entry with a sources list tracking which
    sources have data for it.
    """
    # Build lookup maps
    sensei_map = build_jlptsensei_lookup(jlptsensei_entries)

    # Track patterns we've seen (normalized -> entry)
    unified = {}
    matched_sensei = set()

    # Step 1: Add all GameGengo patterns (they have video timestamps)
    for gg_entry in gamegengo_entries:
        pattern = gg_entry['pattern']
        normalized = normalize_for_matching(pattern)

        sources = [{
            'name': 'gamegengo',
            'timestamp': gg_entry['timestamp'],
            'timestampSeconds': gg_entry['timestampSeconds'],
        }]

        meaning = None

        # Check for JLPTSensei match
        sensei_match = find_jlptsensei_match(pattern, sensei_map)
        if sensei_match:
            sources.append({
                'name': 'jlptsensei',
                'meaning': sensei_match['meaning'],
            })
            meaning = sensei_match['meaning']
            matched_sensei.add(sensei_match['normalized'])

        # Check for DOJG match
        dojg_match = find_dojg_match(pattern, dojg_map)
        if dojg_match:
            sources.append({
                'name': 'dojg',
                'url': dojg_match['url'],
                'meaning': dojg_match.get('meaning'),
            })

        unified[normalized] = {
            'pattern': pattern,
            'jlptLevel': gg_entry['jlptLevel'],
            'meaning': meaning,
            'sources': sources,
        }

    # Step 2: Add JLPTSensei patterns not in GameGengo
    for js_entry in jlptsensei_entries:
        normalized = js_entry['normalized']
        if normalized in unified or normalized in matched_sensei:
            continue

        pattern = js_entry['pattern']

        sources = [{
            'name': 'jlptsensei',
            'meaning': js_entry['meaning'],
        }]

        # Check for DOJG match
        dojg_match = find_dojg_match(pattern, dojg_map)
        if dojg_match:
            sources.append({
                'name': 'dojg',
                'url': dojg_match['url'],
                'meaning': dojg_match.get('meaning'),
            })

        unified[normalized] = {
            'pattern': pattern,
            'jlptLevel': js_entry['jlptLevel'],
            'meaning': js_entry['meaning'],
            'sources': sources,
        }

    # Convert to list with IDs, sorted by JLPT level then pattern
    level_order = {'N5': 0, 'N4': 1, 'N3': 2, 'N2': 3, 'N1': 4}
    entries_list = []
    for i, (norm, entry) in enumerate(sorted(
        unified.items(),
        key=lambda x: (level_order.get(x[1]['jlptLevel'], 9), x[1]['pattern'])
    ), 1):
        # Check if entry has JLPTSensei source
        has_jlptsensei = any(s['name'] == 'jlptsensei' for s in entry['sources'])
        jlptsensei_url = make_jlptsensei_url(entry['pattern']) if has_jlptsensei else None

        entries_list.append({
            'id': f'gram-{i:04d}',
            'pattern': entry['pattern'],
            'jlptLevel': entry['jlptLevel'],
            'meaning': entry['meaning'],
            'sources': entry['sources'],
            'jlptsenseiUrl': jlptsensei_url,
        })

    return entries_list


def main():
    script_dir = Path(__file__).parent
    output_path = script_dir.parent / 'app' / 'src' / 'main' / 'assets' / 'gamegengo-grammar.json'
    dojg_path = script_dir.parent / 'app' / 'src' / 'main' / 'assets' / 'dojg-data.json'
    jlptsensei_path = script_dir / 'data' / 'jlptsensei-grammar.json'

    print("=== Unified Grammar Library Generator ===\n")

    # Step 1: Load all sources
    print("Loading sources...")

    # GameGengo timestamps
    gamegengo_entries = []
    for level, raw_text in [
        ('N5', GAMEGENGO_N5),
        ('N4', GAMEGENGO_N4),
        ('N3', GAMEGENGO_N3),
        ('N2', GAMEGENGO_N2),
    ]:
        entries = parse_gamegengo_timestamps(raw_text, level)
        print(f"  GameGengo {level}: {len(entries)} patterns")
        gamegengo_entries.extend(entries)
    print(f"  GameGengo total: {len(gamegengo_entries)} patterns (N5-N2)")

    # JLPTSensei definitions
    jlptsensei_entries = []
    if jlptsensei_path.exists():
        jlptsensei_entries = load_jlptsensei_data(jlptsensei_path)
        print(f"  JLPTSensei: {len(jlptsensei_entries)} patterns (N5-N1)")
    else:
        print(f"  JLPTSensei: not found at {jlptsensei_path}")

    # DOJG reference URLs
    dojg_map = {}
    if dojg_path.exists():
        dojg_map = load_dojg_data(dojg_path)
        print(f"  DOJG: {len(dojg_map)} patterns")
    else:
        print(f"  DOJG: not found at {dojg_path}")

    # Step 2: Build unified list
    print("\nBuilding unified grammar list...")
    unified_entries = build_unified_grammar(gamegengo_entries, jlptsensei_entries, dojg_map)
    print(f"  Unified total: {len(unified_entries)} unique patterns")

    # Step 3: Build output structure
    output = {
        "version": 4,
        "sources": {
            "gamegengo": {
                "name": "GameGengo",
                "description": "JLPT Grammar Video Textbooks",
                "url": "https://www.youtube.com/@GameGengo"
            },
            "jlptsensei": {
                "name": "JLPTSensei",
                "description": "JLPT grammar definitions",
                "url": "https://jlptsensei.com"
            },
            "dojg": {
                "name": "Dictionary of Japanese Grammar",
                "description": "Reference grammar dictionary series"
            }
        },
        "videos": GAMEGENGO_VIDEOS,
        "grammarPoints": unified_entries
    }

    # Step 4: Write output
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f"\nGenerated: {output_path}")

    # Summary by level
    print("\nSummary by level:")
    for level in ['N5', 'N4', 'N3', 'N2', 'N1']:
        level_entries = [e for e in unified_entries if e['jlptLevel'] == level]
        if not level_entries:
            continue
        with_gg = sum(1 for e in level_entries if any(s['name'] == 'gamegengo' for s in e['sources']))
        with_js = sum(1 for e in level_entries if any(s['name'] == 'jlptsensei' for s in e['sources']))
        with_dojg = sum(1 for e in level_entries if any(s['name'] == 'dojg' for s in e['sources']))
        print(f"  {level}: {len(level_entries)} patterns (GG: {with_gg}, JS: {with_js}, DOJG: {with_dojg})")

    # Source coverage
    print("\nSource coverage:")
    all_with_gg = sum(1 for e in unified_entries if any(s['name'] == 'gamegengo' for s in e['sources']))
    all_with_js = sum(1 for e in unified_entries if any(s['name'] == 'jlptsensei' for s in e['sources']))
    all_with_dojg = sum(1 for e in unified_entries if any(s['name'] == 'dojg' for s in e['sources']))
    print(f"  GameGengo video timestamps: {all_with_gg}")
    print(f"  JLPTSensei definitions: {all_with_js}")
    print(f"  DOJG references: {all_with_dojg}")


if __name__ == '__main__':
    main()
