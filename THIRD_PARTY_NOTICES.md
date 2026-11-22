# Third-party Notices

Pastiera is licensed under the GNU General Public License version 3. Some components are derived from third-party open source projects and retain their original attribution requirements.

## Android Open Source Project LatinIME

- Project: Android Open Source Project, LatinIME (`platform/packages/inputmethods/LatinIME`)
- Source repository: https://android.googlesource.com/platform/packages/inputmethods/LatinIME
- Pinned source revision used as reference: `127336e9f29d69607eab55982324b210279ae8c5`
- License: Apache License, Version 2.0
- License text: `third_party/licenses/Apache-2.0.txt`

### Derived scope

Pastiera's full virtual keyboard mode uses AOSP LatinIME as a reference for keyboard geometry and visual assets:

- `app/src/main/java/rocks/pkb/plektra/inputmethod/aospkeyboard/AospKeyboardView.kt`
- AOSP-derived keyboard background, key, spacebar, preview, popup and selected-popup `.9.png` resources under:
  - `app/src/main/res/drawable-mdpi/`
  - `app/src/main/res/drawable-hdpi/`
  - `app/src/main/res/drawable-xhdpi/`
  - `app/src/main/res/drawable-xxhdpi/`
  - `app/src/main/res/drawable-xxxhdpi/`

### Non-derived scope

Pastiera does not import AOSP dictionaries. Suggestions, status bars, prediction bars, IME lifecycle, settings, PKB behavior and custom dictionaries remain Pastiera-specific unless separately documented.

## OpenGameArt Typing Sounds

Pastiera includes short typing sound samples derived from CC0 sound effects.

### Keyboard Soundpack #1 [Typing and Single Keystrokes]

- Author: unicaegames
- Source: https://opengameart.org/content/keyboard-soundpack-1-typing-and-single-keystrokes
- License: Creative Commons Zero 1.0 Universal (CC0 1.0)
- Derived files:
  - `app/src/main/res/raw/typing_click_1.ogg`
  - `app/src/main/res/raw/typing_click_2.ogg`
  - `app/src/main/res/raw/typing_click_3.ogg`
  - `app/src/main/res/raw/typing_click_4.ogg`

### Typewriter sounds

- Author: Cassie-OrbitGames
- Source: https://opengameart.org/content/typewriter-sounds
- License: Creative Commons Zero 1.0 Universal (CC0 1.0)

### Mechanical Sounds

- Author: BMacZero
- Source: https://opengameart.org/content/mechanical-sounds
- License: Creative Commons Zero 1.0 Universal (CC0 1.0)

Typing sound derivatives are included under `app/src/main/res/raw/typing_*.ogg`.

## Unicode CLDR Emoji Annotations

- Project: Unicode Common Locale Data Repository (CLDR)
- Source repository: https://github.com/unicode-org/cldr-json
- Source path used by generator: `cldr-json/cldr-annotations-full/annotations`
- License: Unicode License v3 (SPDX `Unicode-3.0`), copyright © 2015-2024 Unicode, Inc.; full copyright and permission notice in `third_party/licenses/Unicode-3.0-CLDR.txt` (copy of `LICENSE` from cldr-json 49.0.0)

Pastiera's local emoji search assets under `app/src/main/assets/common/emoji_search/*.tsv`
are generated from Unicode CLDR annotation data (CLDR 49.0.0) by `scripts/update_emoji_assets.py`.
The generated TSV files are filtered to the emoji set bundled with Pastiera and normalized
for compact local lookup.

## Unicode Emoji and Unicode Character Database

- Project: Unicode Emoji 18.0 (`emoji-test.txt`) and Unicode Character Database 18.0 (`UnicodeData.txt`, `emoji-data.txt`)
- Source: https://www.unicode.org/Public/18.0.0/
- License: Unicode License v3 (SPDX `Unicode-3.0`), copyright © 1991-2026 Unicode, Inc.; full copyright and permission notice in `third_party/licenses/Unicode-3.0.txt` (copy of https://www.unicode.org/license.txt)

The emoji categories under `app/src/main/assets/common/emoji/*.txt`, the symbol list `app/src/main/assets/common/symbols/symbols.tsv`
and the localized symbol names under `app/src/main/assets/common/symbol_search/*.tsv` (CLDR annotations) are generated
by `scripts/update_emoji_assets.py`.

## Noto Color Emoji

- Project: Noto Emoji, release `v2026-09-24-unicode18_0` (font version 2.057)
- Source: https://github.com/googlefonts/noto-emoji
- File: `app/src/main/assets/fonts/NotoColorEmoji-emojicompat.ttf` (unmodified)
- License: SIL Open Font License, Version 1.1 (`third_party/licenses/OFL-1.1.txt`)

Pastiera draws emoji in its own views with this font (offline, through AndroidX EmojiCompat).

## Flux Keyboard

- Project: Flux Keyboard 2.0.0
- Source: https://github.com/Flux-Sniffer-Mods/Flux-Keyboard
- License: GNU General Public License version 3

The kaomoji collection `app/src/main/assets/common/kaomoji/kaomoji.txt` (German search words added) and the
emoji/symbol/kaomoji pages, default skin tone and symbol pages are adapted from Flux Keyboard.

## Leipzig Corpora Collection / Wortschatz Leipzig

- Project: Leipzig Corpora Collection / Wortschatz Leipzig
- Project pages: https://corpora.uni-leipzig.de/ and https://wortschatz.uni-leipzig.de/
- Companion asset repository: https://github.com/pkb-rocks/plektra-dict
- License: Creative Commons attribution terms for the downloaded corpus/frequency-list data;
  the Leipzig frequency dictionary word lists are documented as CC-BY 3.0, while current
  downloadable corpus data may carry corpus-specific terms.

Pastiera's bundled base dictionaries under `app/src/main/assets/common/dictionaries/*_base.json`
and the generated serialized dictionaries under `app/src/main/assets/common/dictionaries_serialized/*_base.dict`
are frequency-list derivatives built mainly from Leipzig Corpora Collection / Wortschatz Leipzig
word-frequency data, with project-maintained filtering, truncation, normalization, and additional
entries. The companion `pastiera-dict` repository distributes larger downloadable `.dict` assets
and their manifests. Conversion and serialization scripts live under `scripts/`.

Provenance note: the maintained dictionary pipeline and current maintainer-provided sources point
mainly to Leipzig/Wortschatz frequency data. Some older bundled entries predate the current
documentation trail, so their exact upstream corpus IDs are not fully reconstructed here.

## Plektra Custom: British English prediction data

The Plektra Custom fork (MDelar/plektra_custom) adds generated British English assets built by
`tools/british_english/build_en_gb_assets.py`:

- `app/src/main/assets/common/dictionaries/en_gb_overlay.tsv`
- `app/src/main/assets/common/autocorrect/en_gb_spelling.json`
- `app/src/main/assets/common/ngrams/en_gb_bigrams.bin`

They contain word lists, spelling pairs and word-pair probabilities only, not source text.

### VarCon (English Speller Database / SCOWL)

- Source: https://github.com/en-wl/wordlist (branch `v1`, `varcon/varcon.txt`)
- Copyright 2000-2026 by Kevin Atkinson. Permission to use, copy, modify, distribute, and sell any part of the database, or word lists created from it, is granted without fee, provided the copyright notice and this permission notice appear in all copies and supporting documentation. Provided "as is" without express or implied warranty.
- Used for: American to British spelling pairs.

### symspellpy bigram frequency list / Google Books Ngram

- Source: `frequency_bigramdictionary_en_243_342.txt` from the symspellpy package (https://github.com/mammothb/symspellpy, MIT License)
- The list is derived from the Google Books Ngram data (https://books.google.com/ngrams), licensed under CC BY 3.0.
- Used for: background word-pair probabilities, converted to British spelling.

### Topical-Chat

- Source: https://github.com/alexa/Topical-Chat, Copyright Amazon.com, Inc. or its affiliates
- License: Community Data License Agreement – Sharing – Version 1.0. Only statistical Results (word-pair counts) are published; under section 3.5 the agreement imposes no obligations on Results.

### Taskmaster

- Source: https://github.com/google-research-datasets/Taskmaster, by Bill Byrne, Karthik Krishnamoorthi, Chinnadhurai Sankar, Arvind Neelakantan, Amit Dubey, Kyu-Young Kim, Andy Cedilnik and Saravanan Ganesh (Google LLC)
- License: Creative Commons Attribution 4.0 (https://creativecommons.org/licenses/by/4.0/)
- Used for: word-pair counts (aggregated, converted to British spelling).

### British phrase list

- `tools/british_english/uk_seed_corpus.txt` was written for Plektra Custom and is licensed GPL-3.0 with the app.
