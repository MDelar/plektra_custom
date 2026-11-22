#!/usr/bin/env python3
"""Build the British English assets used by Plektra Custom.

Outputs (paths relative to app/src/main/assets):
  common/dictionaries/en_gb_overlay.tsv   word frequency tweaks applied on top of en_base.dict
  common/autocorrect/en_gb_spelling.json  US -> UK exact spelling replacements
  common/ngrams/en_gb_bigrams.bin         built-in next-word model (see BuiltInBigramModel.kt)

Sources (not committed, fetch them yourself):
  --varcon        VarCon varcon.txt from https://github.com/en-wl/wordlist (branch v1)
  --web-bigrams   frequency_bigramdictionary_en_243_342.txt from the symspellpy wheel (MIT,
                  derived from Google Books Ngram data, CC BY 3.0)
  --topical-chat  https://github.com/alexa/Topical-Chat (CDLA-Sharing-1.0; counts are "Results")
  --taskmaster    https://github.com/google-research-datasets/Taskmaster (CC BY 4.0)
  --seed          tools/british_english/uk_seed_corpus.txt (written for this project)

Every source is converted to British spelling before counting.

Usage:
  pip install cbor2
  python3 tools/british_english/build_en_gb_assets.py \
      --dict app/src/main/assets/common/dictionaries_serialized/en_base.dict \
      --varcon ~/src/scowl1/varcon/varcon.txt \
      --web-bigrams ~/src/symspellpy/frequency_bigramdictionary_en_243_342.txt \
      --topical-chat ~/src/Topical-Chat --taskmaster ~/src/Taskmaster \
      --seed tools/british_english/uk_seed_corpus.txt \
      --assets app/src/main/assets
"""
import argparse
import collections
import glob
import json
import math
import os
import re
import struct
import sys

import cbor2

MAX_VARCON_LEVEL = 70
MIN_FREQ = 66  # lowest frequency used in en_base.dict
TOP_FOLLOWERS = 40
MAX_PREV_WORDS = 40000
SENTENCE_START_ENTRIES = 48
P_MIN = 1e-5

# Everyday British words missing from en_base.dict (word, frequency on the 0-255 scale).
UK_ADDITIONS = {
    "telly": 92, "cuppa": 88, "innit": 80, "knackered": 88, "loo": 90, "brekkie": 72,
    "fiver": 84, "tenner": 84, "chuffed": 86, "skint": 82, "faff": 80, "naff": 74,
    "brolly": 72, "hiya": 96, "yep": 112, "ok": 120, "lol": 104, "laters": 74,
    "mardy": 68, "bloke": 92, "lads": 98, "lass": 84, "cheeky": 92, "lush": 86,
    "takeaway": 98, "postcode": 96, "Mum": 96, "Tesco": 98, "Sainsbury's": 90,
    "Asda": 90, "Morrisons": 86, "Aldi": 88, "Lidl": 88, "Greggs": 84, "Argos": 84,
    "Primark": 82, "A-levels": 86, "GCSEs": 92, "uni": 98, "maths": 112,
}

# Lexical British preferences that only change ranking: (UK word, US word).
UK_RANKING_SWAPS = [
    ("mum", "mom"), ("mummy", "mommy"), ("mums", "moms"), ("mum's", "mom's"),
    ("holiday", "vacation"), ("holidays", "vacations"), ("rubbish", "garbage"),
    ("lorry", "truck"), ("pavement", "sidewalk"), ("biscuits", "cookies"),
    ("trainers", "sneakers"), ("jumper", "sweater"),
]
# US words that stay common in British English: the UK word is raised but these keep their rank.
KEEP_US_RANK = {"truck", "cookies"}

# US-preferred forms that are also normal in British English (or in UK science/computing
# teaching), so they are never removed or auto-corrected.
PROTECTED_US_FORMS = {
    "among", "learned", "spelled", "burned", "dreamed", "leaped", "spoiled", "spilled", "smelled",
    "inquiry", "inquiries", "inquire", "judgment", "judgments", "specialty", "specialties",
    "sulfur", "sulfate", "sulfates", "sulfide", "sulfides", "sulfuric", "sulfurous", "sulfur's",
    "analog", "dialog", "dialogs", "disk", "disks", "program", "programs", "programmed",
    "programming", "programmer", "programmers", "while", "amid",
}

TOKEN_RE = re.compile(r"[A-Za-z0-9]+(?:['’][A-Za-z]+)*|[.!?]+|\n")


def load_dictionary(path):
    data = cbor2.load(open(path, "rb"))
    words = {}
    for entries in data["normalizedIndex"].values():
        for entry in entries:
            if entry.get("source", 0) == 0:
                words[entry["word"]] = max(words.get(entry["word"], 0), entry["frequency"])
    return words


def parse_varcon(path):
    """Returns (us_only, ambiguous): dicts mapping lowercase US spelling -> UK spelling."""
    pairs = collections.defaultdict(set)
    british_words = set()
    level = 100
    for raw in open(path, encoding="latin-1"):
        line = raw.strip()
        if line.startswith("# "):
            m = re.search(r"\(level (\d+)\)", line)
            level = int(m.group(1)) if m else 100
            continue
        if not line or line.startswith("#") or level > MAX_VARCON_LEVEL:
            continue
        line = line.split("|")[0].strip()
        american = None
        british = None
        for group in line.split("/"):
            if ":" not in group:
                continue
            tags, word = group.split(":", 1)
            word = word.strip()
            tag_list = tags.split()
            if "A" in tag_list and american is None:
                american = word
            if "B" in tag_list:
                british_words.add(word.lower())
                if british is None:
                    british = word
        if american and british and american != british:
            if american[0].isupper() or " " in american or " " in british:
                continue
            pairs[american.lower()].add(british)
    us_only = {}
    ambiguous = {}
    for us, uks in pairs.items():
        if us in PROTECTED_US_FORMS:
            continue
        if len(uks) != 1:
            continue
        uk = next(iter(uks))
        if us in british_words:
            ambiguous[us] = uk
        else:
            us_only[us] = uk
    return us_only, ambiguous


def match_case(template, word):
    if template.isupper() and len(template) > 1:
        return word.upper()
    if template[:1].isupper():
        return word[:1].upper() + word[1:]
    return word


def build_overlay(words, us_only, ambiguous):
    # Only exact lowercase matches: "Dora" or "Manila" must never be touched by "dora"/"manila".
    lower_to_word = {word: word for word in words if word == word.lower()}
    sets = {}
    removes = set()
    for us, uk in us_only.items():
        us_word = lower_to_word.get(us)
        if us_word is None:
            continue
        us_freq = words[us_word]
        uk_freq = words.get(uk, 0)
        sets[uk] = max(sets.get(uk, 0), uk_freq, us_freq + 1)
        removes.add(us_word)
    for us, uk in ambiguous.items():
        us_word = lower_to_word.get(us)
        if us_word is None or uk in words:
            continue
        sets[uk] = max(sets.get(uk, 0), max(MIN_FREQ, words[us_word] - 12))
    for word, freq in UK_ADDITIONS.items():
        if word not in words or words[word] < freq:
            sets[word] = max(sets.get(word, 0), freq)
    for uk, us in UK_RANKING_SWAPS:
        uk_freq = sets.get(uk, words.get(uk, 0))
        us_freq = words.get(us, 0)
        if uk_freq and us_freq and us_freq >= uk_freq:
            sets[uk] = us_freq + 2
            if us not in KEEP_US_RANK:
                sets[us] = max(MIN_FREQ, uk_freq - 2)
    for word in removes:
        sets.pop(word, None)
    return sets, removes


class Converter:
    """Converts tokens to British spelling, preserving capitalisation."""

    def __init__(self, us_only):
        self.us_only = us_only

    def __call__(self, token):
        uk = self.us_only.get(token.lower())
        return match_case(token, uk) if uk else token


def iter_texts_topical(root):
    for path in glob.glob(os.path.join(root, "conversations", "*.json")):
        data = json.load(open(path))
        for conv in data.values():
            for turn in conv["content"]:
                yield turn["message"]


def iter_texts_taskmaster(root):
    for path in glob.glob(os.path.join(root, "**", "*.json"), recursive=True):
        name = os.path.basename(path)
        if "ontology" in path or name in ("sample.json",) or "reward" in path or "splits" in path:
            continue
        try:
            data = json.load(open(path))
        except Exception:
            continue
        if not isinstance(data, list):
            continue
        for dialog in data:
            if not isinstance(dialog, dict):
                continue
            for utt in dialog.get("utterances", []):
                text = utt.get("text")
                if text:
                    yield text


def iter_seed(path):
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            yield line


class Counter:
    def __init__(self, vocab_keys, convert):
        self.vocab_keys = vocab_keys
        self.convert = convert
        self.bigrams = collections.defaultdict(collections.Counter)
        self.starts = collections.Counter()
        self.surface = collections.defaultdict(collections.Counter)

    def add_text(self, text, weight=1):
        prev = None
        at_start = True
        for token in TOKEN_RE.findall(text.replace("’", "'")):
            if token == "\n" or token[0] in ".!?":
                prev = None
                at_start = True
                continue
            token = self.convert(token)
            key = token.lower()
            if key not in self.vocab_keys:
                prev = None
                at_start = False
                continue
            if not at_start:
                self.surface[key][token] += weight
            if at_start:
                self.starts[key] += weight
            elif prev is not None:
                self.bigrams[prev][key] += weight
            prev = key
            at_start = False


def distributions(counter_map, min_count):
    out = {}
    for prev, followers in counter_map.items():
        total = sum(followers.values())
        if total < min_count:
            continue
        out[prev] = {w: c / total for w, c in followers.items() if c >= min_count}
    return out


def blend(sources):
    """sources: list of (weight, {prev: {next: p}}). Per-prev renormalised mixture."""
    prevs = set()
    for _, dist in sources:
        prevs.update(dist)
    merged = {}
    for prev in prevs:
        present = [(w, d[prev]) for w, d in sources if prev in d and d[prev]]
        if not present:
            continue
        total_w = sum(w for w, _ in present)
        mix = collections.defaultdict(float)
        for w, followers in present:
            for nxt, p in followers.items():
                mix[nxt] += p * w / total_w
        merged[prev] = mix
    return merged


def quantise(p):
    p = max(P_MIN, min(1.0, p))
    return int(round(255 * (math.log(p) - math.log(P_MIN)) / (-math.log(P_MIN))))


def write_model(path, display, merged, starts):
    vocab = sorted(display)
    index = {key: i for i, key in enumerate(vocab)}
    if len(vocab) >= 65536:
        raise SystemExit("vocabulary too large for u16 indices")
    prevs = sorted(merged, key=lambda k: -sum(merged[k].values()))
    with open(path, "wb") as f:
        f.write(b"PKBG")
        f.write(struct.pack(">BI", 1, len(vocab)))
        for key in vocab:
            data = display[key].encode("utf-8")
            f.write(struct.pack(">B", len(data)))
            f.write(data)
        top_starts = starts.most_common(SENTENCE_START_ENTRIES)
        total = sum(starts.values()) or 1
        f.write(struct.pack(">H", len(top_starts)))
        for key, count in top_starts:
            f.write(struct.pack(">HB", index[key], quantise(count / total)))
        rows = []
        for prev in prevs:
            if prev not in index:
                continue
            followers = sorted(merged[prev].items(), key=lambda kv: -kv[1])
            followers = [(n, p) for n, p in followers if n in index and quantise(p) > 0][:TOP_FOLLOWERS]
            if followers:
                rows.append((prev, followers))
        f.write(struct.pack(">I", len(rows)))
        for prev, followers in rows:
            f.write(struct.pack(">HB", index[prev], len(followers)))
            for nxt, p in followers:
                f.write(struct.pack(">HB", index[nxt], quantise(p)))
    return len(vocab), len(rows), sum(len(f) for _, f in rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dict", required=True)
    ap.add_argument("--varcon", required=True)
    ap.add_argument("--web-bigrams", required=True)
    ap.add_argument("--topical-chat", required=True)
    ap.add_argument("--taskmaster", required=True)
    ap.add_argument("--seed", required=True)
    ap.add_argument("--assets", required=True)
    args = ap.parse_args()

    words = load_dictionary(args.dict)
    us_only, ambiguous = parse_varcon(args.varcon)
    sets, removes = build_overlay(words, us_only, ambiguous)

    overlay_path = os.path.join(args.assets, "common/dictionaries/en_gb_overlay.tsv")
    with open(overlay_path, "w", encoding="utf-8") as f:
        f.write("# Plektra Custom British English overlay for en_base.dict.\n")
        f.write("# Generated by tools/british_english/build_en_gb_assets.py - do not edit by hand.\n")
        f.write("# set<TAB>word<TAB>frequency (0-255) | remove<TAB>word\n")
        for word in sorted(removes):
            f.write(f"remove\t{word}\n")
        for word in sorted(sets):
            f.write(f"set\t{word}\t{sets[word]}\n")

    spelling = {}
    final_words = dict(words)
    for word in removes:
        final_words.pop(word, None)
    final_words.update(sets)
    for us, uk in us_only.items():
        if uk in final_words:
            spelling[us] = uk
    spelling_path = os.path.join(args.assets, "common/autocorrect/en_gb_spelling.json")
    with open(spelling_path, "w", encoding="utf-8") as f:
        json.dump(dict(sorted(spelling.items())), f, ensure_ascii=False, indent=0)
        f.write("\n")

    # Display form per lowercase key: the most frequent dictionary casing.
    display = {}
    for word, freq in final_words.items():
        if len(word.encode("utf-8")) > 255:
            continue
        key = word.lower()
        if key not in display or freq > final_words[display[key]]:
            display[key] = word
    convert = Converter(us_only)
    vocab_keys = set(display)

    conv = Counter(vocab_keys, convert)
    tasks = Counter(vocab_keys, convert)
    n = 0
    for text in iter_texts_topical(args.topical_chat):
        conv.add_text(text)
        n += 1
    for text in iter_texts_taskmaster(args.taskmaster):
        tasks.add_text(text)
        n += 1
    print(f"conversational texts: {n}", file=sys.stderr)

    seed = Counter(vocab_keys, convert)
    for text in iter_seed(args.seed):
        seed.add_text(text)

    web = collections.defaultdict(collections.Counter)
    for line in open(args.web_bigrams, encoding="utf-8"):
        parts = line.split()
        if len(parts) != 3:
            continue
        a, b = convert(parts[0]).lower(), convert(parts[1]).lower()
        if a in vocab_keys and b in vocab_keys:
            web[a][b] += int(parts[2])

    merged = blend([
        (0.40, distributions(conv.bigrams, 3)),
        (0.15, distributions(tasks.bigrams, 3)),
        (0.25, distributions(web, 1)),
        (0.35, distributions(seed.bigrams, 1)),
    ])

    starts = collections.Counter()
    conv_total = sum(conv.starts.values()) or 1
    seed_total = sum(seed.starts.values()) or 1
    for key, c in conv.starts.items():
        starts[key] += c / conv_total
    for key, c in seed.starts.items():
        starts[key] += c / seed_total
    task_total = sum(tasks.starts.values()) or 1
    for key, c in tasks.starts.items():
        starts[key] += 0.3 * c / task_total
    starts = collections.Counter({k: int(v * 1e6) for k, v in starts.items() if v * 1e6 >= 1})

    # Prefer corpus casing for display when it is consistent (e.g. "I", "Monday").
    for key, forms in conv.surface.items():
        form, count = forms.most_common(1)[0]
        if form != display[key] and count >= 0.8 * sum(forms.values()) and form.lower() == key:
            if form[0].isupper() and display[key][0].islower() and key not in ("i",):
                continue
            display[key] = form

    # Keep the most useful previous words only.
    if len(merged) > MAX_PREV_WORDS:
        keep = sorted(merged, key=lambda k: -sum(conv.bigrams[k].values()) - sum(tasks.bigrams[k].values()) - sum(web[k].values()) / 1e6)
        merged = {k: merged[k] for k in keep[:MAX_PREV_WORDS]}

    os.makedirs(os.path.join(args.assets, "common/ngrams"), exist_ok=True)
    model_path = os.path.join(args.assets, "common/ngrams/en_gb_bigrams.bin")
    used = set(merged)
    for followers in merged.values():
        used.update(followers)
    used.update(starts)
    model_display = {k: display[k] for k in used if k in display}
    stats = write_model(model_path, model_display, merged, starts)
    print(f"overlay: {len(sets)} set, {len(removes)} removed; spelling map: {len(spelling)}", file=sys.stderr)
    print(f"model: vocab={stats[0]} prev={stats[1]} pairs={stats[2]} bytes={os.path.getsize(model_path)}",
          file=sys.stderr)


if __name__ == "__main__":
    main()
