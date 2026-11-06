# 2D text structure: line breaks, ruby and asides (research #428)

Research and desktop runs, 2026-10-30. The issue asks whether three kinds of structure that Japanese manga prints in two dimensions survive Yomu's pipeline, and whether they should: line breaks inside a bubble, ruby (furigana) beside the main text, and asides tied to a bubble but printed beside or outside it.

**What was run, and on what.**

- **Nothing ran on a phone or an emulator.** Every number below comes from a desktop replica of Yomu's detector and OCR (Python, onnxruntime 1.31.1, Apple M4 Pro CPU) using the exact ONNX files the app pins (`scripts/speed-fixtures/models/vision/*.url`: `Kiuyha/Manga-Bubble-YOLO@fb64650` `onnx/yolo26n.onnx`, `artsaraiva/manga-ocr-2025-onnx-past@1518987`). The replica ports the letterbox, the 0.25 threshold, NMS with the #370 swallow step, the crop, the 224×224 resize, the greedy decode from `[CLS]`, the #366 `unsure` gate and `ContextAssembler`'s reading order.
- **The pages are OpenMantra's 214 Japanese pages, read at their original resolution (828×1170),** not the 1280×2856 emulator screenshots the app sees. Readings therefore differ from an app run in places, as [#350's note](jev-bubble-class-hints.md#6-real-ocr-from-the-emulator-and-the-validator-idea) found on the fixture pages. OpenMantra is CC BY-NC 4.0 (`vendor/open-mantra-dataset/LICENSE.md`); its annotation is used only as a reference transcription.
- **I counted ruby and asides by eye.** A geometric filter proposed candidates; I looked at every candidate crop. Line breaks were counted by a projection-profile column counter, which I checked by eye on 28 boxes (27 right).
- **The LLM tests ran on desktop llama.cpp:** first on Qwen2.5-1.5B ([§6](#6-desktop-llm-test-qwen25-15b)), then on the seven 2–4B catalog entries ([§7](#7-the-24b-catalog-models)), each with its pinned GGUF, template and sampling. The 8B and 9B entries and the aside prompt test did not run; they are listed as open.

## Questions, and where they are answered

| Issue question | Where |
|---|---|
| 1. How often each kind appears, and what the pipeline makes of it | [§1](#1-frequency-and-what-yomu-produces-today) |
| 2. Line breaks: can OCR keep them, do models do better with them | [§2](#2-line-breaks) |
| 3. Ruby: can it be told apart, do models use a marked form | [§3](#3-ruby) |
| 4. Asides: geometry-only linking, prompt mark, rendering | [§4](#4-asides) |
| 5. An existing phone-sized model with a shippable licence; else what training takes | [§5](#5-existing-models-and-what-training-would-take) |
| Recommendations | [Recommendations](#recommendations) |

## Short answer

- **Line breaks are everywhere and almost never carry meaning that the text itself loses.** 61% of detector boxes (886 of 1,456) have two or more printed columns. In a random sample of 40 multi-column boxes, 5 had a break that marks a boundary with no punctuation (`はぁ / はぁ`, `えっ / そうなんですか!?`, `かかっ / よくぞ…`). The rest break between phrases of one sentence. MangaOCR is trained to drop breaks, and reading per column cost accuracy (CER 4.9% → 6.6%).
- **Ruby is rare, concentrated, and always dropped.** 20 of 1,456 boxes (1.4%) carry ruby, 14 of them in one battle manga (`rasetugari`). 9 are meaning rubies (`闇(せかい)`, `銃(ツミ)と閉じ眼(カクゴ)`, `薬店(うち)`, `平安の世より在藤(お前ら)に仕えて`), 11 are readings, mostly of names (`和音(かずね)`, `呉葉(くれは)`, `在藤宏也(ありふじひろや)`). The OCR dropped the ruby in every case and kept a confidence of 0.93–1.0, so the #366 gate never notices.
- **Asides exist, but the pipeline's real problem with them is merging, not marking.** Over the 62 small bubbles attached to a bigger one, Yomu's detector merged 24 into the neighbour's box, boxed 16 on their own and missed 22. Unbubbled asides (`指なくなっちゃう…` beside `できる気がしないよぉ…`) were merged in the same way.
- **One existing phone-sized detector already outputs a bubble class:** `ogkalu/comic-text-and-bubble-detector`, `detector-v4-s_int8.onnx` (11 MB, Apache-2.0 on the card), with classes `bubble`, `text_bubble` and `text_free`. On the 214 pages it took 0.05 s a page on desktop CPU and separated the merged bubbles above. No shippable model outputs ruby or line structure as a class.
- **Whether a model uses marked ruby depends on the model.** Given `base(ruby)`, the default Qwen2.5-1.5B mostly turns the ruby into a bracketed label ("the gun (Tsumi) and the blindfold (Kakugo)", "the harmony (かずね)") or ignores it. So do the Qwen3.5 models and Ministral. Gemma 4 E4B, with one sentence explaining the notation, used it in 18 of 32 lines and bracketed none ("Let go of Kazune!!", "Hiroya Arifuji", "my sister Kazune", "Serving you from the age of Heian"). Gemma 4 E2B, Hy-MT2 and Qwen3 4B 2507 fall in between. Reading ruby, mostly names, is what comes through; the hardest meaning rubies (`銃(ツミ)`, `薬店(うち)`, `呪い(つば)`) came through in no model.
- **The OCR Yomu already ships reads a ruby crop correctly** (16 of 19 exact). Finding the ruby column is the weak step: the projection rule's 51 candidates held 19 real ones.
- **An ideographic space at column breaks is safe and slightly helpful on every model** (content checks up 0–8 of 76, almost no malformed replies). ` / ` broke replies on every model.
- **Recommendations:**
  - **Line breaks:** do not build a break-recovery stage now. The gain is small and the break positions cost an extra OCR pass per column.
  - **Ruby:** worth building only together with a stronger column finder, and only for models that use it (Gemma 4 E4B first); the default model should keep receiving plain text. Deferred as #438, because it touches about 1% of boxes and helps one optional model today.
  - **Asides:** do not mark them. Evaluate the ogkalu detector as a bubble-aware detector (#437), because its bubble boxes fix the merges that cause the visible harm.

## How a page reaches the model today (verified on `main`, `ba91c31`)

1. **Detection.** `BubbleDetector.detect` returns one `Text` box per text region, single class (`pipeline/src/main/java/com/yomu/pipeline/bubble/BubbleDetector.kt:45-72`; ADR-0007). A box mostly inside another is suppressed at 0.90 containment (`:29`), so even a detector that boxed ruby on its own would lose that box to the column box beside it.
2. **OCR.** Each box is cropped as is (`pipeline/src/main/java/com/yomu/pipeline/TranslationPipeline.kt:179-195`), squashed to 224×224 (`pipeline/src/main/java/com/yomu/pipeline/ocr/OcrEngine.kt:86-90`) and decoded greedily into one string (`ocr/OcrDecoding.kt:43-58`). Reads under 0.85 mean token probability are skipped (`OcrEngine.kt:14,23`; `TranslationPipeline.kt:200`).
3. **Reading order.** `ContextAssembler` splits the page at paper gutters and orders boxes right to left, top to bottom (`context/ContextAssembler.kt:65-92`). An aside's position in the prompt is wherever its box falls in that order.
4. **Prompt.** `buildBatchPrompt` writes one `[id] text` line per box, `---` between panels (`ml/src/main/java/com/yomu/ml/LlamaTranslationBridge.kt:231-239`). The batch grammar allows one line of up to 160 characters per id, excluding newlines and `[` (`:254-258`, `:71`). A reply can therefore carry `(` and `《` but never a line break.
5. **Rendering.** `Typesetter.typeset` collapses all whitespace in the translation (`pipeline/src/main/java/com/yomu/pipeline/typesetting/Typesetter.kt:41,78-80`) and re-wraps it horizontally into the bubble's render box. Any break structure in the reply would be thrown away here too.

## 1. Frequency, and what Yomu produces today

| Kind | How often (214 pages, 1,456 detector boxes) | What today's pipeline sends the model |
|---|---|---|
| Line break (2+ columns in a box) | 886 boxes (61%); 1 column 549, 2 columns 475, 3 columns 309, 4+ columns 102, unreadable 21 | One string, columns concatenated |
| Break that marks a boundary punctuation does not | 5 of a random 40 multi-column boxes (≈12%) | Same; e.g. `はぁはぁ`, `かかっよくぞ...` |
| Ruby | 20 boxes (1.4%): rasetugari 14, tojime 5, tencho 1, balloon 0, bourei 0 | Base text only; ruby dropped in all 19 read boxes |
| Meaning ruby | 9 of the 20 | Kanji only: the meaning the author wrote is lost |
| Reading ruby | 11 of the 20, 8 of them names | Kanji only: the model must guess the name |
| Small bubble attached to a bigger one | 62 (ogkalu `bubble` boxes within 15 px of one at least 2.5× larger) | Merged into the neighbour 24, own line 16, missed 22 |
| Unbubbled aside beside a bubble | 4 confirmed by eye among the 73 `text_free` boxes, all in `tencho_isoro` | Own line 2, merged into the bubble's box 2 |

### Line breaks: examples

The OCR output joins the columns; the right-hand column is the page.

| Page | Columns as printed | Yomu's read |
|---|---|---|
| tojime 021 | `てっ` / `思ったより` / `窓が高かった…` | `てっ思ったより窓が高かった...` |
| tojime 018 | `はっ` / `無駄だよ` / `レーネ` | `はっ無駄だよレーネ` |
| rasetugari 014 | `ばっ` / `馬鹿な…` | `ばっ馬鹿な...` |
| balloon 013 | `えっ` / `そうなん` / `ですか!?` | `えっそうなんですか!?` |
| tencho (box 716) | `そう` / `それ!` | `そうそれ!` |
| bourei 009 | `他人から` / `嫌われるのには` / `慣れてるッ` ‖ `でも` / `妹には` / `引かれたく` / `ないの` | `他人から嫌われるのには慣れてるッでも嫁には引かれたくないの` (two bubbles in one box, 妹 misread as 嫁) |
| bourei 003 caption | `烏丸葬儀社社長` / `烏丸　柩(からすま　くるる)　25歳` | `烏丸御能お社長烏丸・枢(からすまくるる)25歳` |

Most breaks are like `ひどい父親さ… / しがない絵描き / でね` (tojime): a letterer wrapping one sentence at phrase boundaries. The bourei caption is the only one in the sample where the break separates two different kinds of information, a job title and a name.

### Ruby: every instance found

Ruby sits to the right of its vertical column, at about half the main glyph width. OpenMantra's own transcription also leaves it out, except where the page prints a reading inline in parentheses.

| Box | Page prints | Kind | Yomu's read (mean p) | OpenMantra's professional English |
|---|---|---|---|---|
| tojime 002 | `トルティヤーノ一家(ファミリー)の方` | meaning (gloss) | `…トルティヤーノ一家の方でしょうか` (0.999) | "a member of the Tortillano family" |
| tojime 003 | `トルティヤーノ一家(ファミリー)の名` | meaning (gloss) | `…トルティヤー!一家の名…` (0.974) | — |
| tojime 013 | `銃(ツミ)と閉じ眼(カクゴ)しかないというのに` | meaning: "gun" read as "sin", "closed eyes" as "resolve" | `銃と閉じ眼しかないというのに` (0.998) | "if I put down my guns...?" |
| tojime 031 | `閉じ眼の闇(せかい)ですから` | meaning: "darkness" read as "world" | `閉じ目の闇ですから` (0.987) | "i'm the only one who "sees" the situation here" |
| tojime 038 | `確(しか)とお覚悟を` | reading | `確とお覚悟を` (1.0) | "you have to be prepared." |
| tojime 029 title | `綴じ眼の撃ち手(バウンドアイ・ガンナー)` | meaning (title) | not checked | — |
| tencho 020 | `腹をこわして薬店(うち)にかけこみ` | meaning: "pharmacy" read as "our place" | `腹をこわして薬店にかけこみ` (0.997) | "ran into my store with a stomachache" |
| rasetugari 001 | `在藤宏也(ありふじひろや)` | reading (name) | `在藤宏也` (0.984) | "hiroya arifuji," |
| rasetugari 001 | `呉葉(くれは)` | reading (name) | `呉葉` (0.999) | "Kureha" |
| rasetugari 005, 007 | `和音(かずね)を離せ!!`, `和音(かずね)を…!!` | reading (name) | `和音を離せ!!`, `和音を...!!` (1.0, 0.999) | "let go of kazune!!" |
| rasetugari 012 | `尚(ナオ)――` | reading | `方法尚―` (0.755, skipped as unsure) | "furthermore" |
| rasetugari 012 | `対象ノ鬼号(きごう)――` | reading | `対象ノ鬼号――` (0.926) | "the demon's target ID is" |
| rasetugari 013 | `華鏡丸(かきょうまる)` | reading (name) | `華鏡丸` (0.997) | "Kakyomaru" |
| rasetugari 020 | `呉葉の『極焔(ごくえん)』` | reading (technique) | `…呉葉の『極格』` (0.992) | "this skill "Goku-en"" |
| rasetugari 024 | `幻術で妹(かずね)を…まずい!` | meaning: "little sister" read as her name | `幻術で妹を...まずい!` (0.999) | "giving an illusion of his sister...damn it!" |
| rasetugari 028 | `俺は在藤(お前)を――` | meaning: the family name read as "you" | `俺は在隣をー` | — |
| rasetugari 033 | `十年前につけた呪い(つば)` | meaning: "curse" read as "spit" (as in 唾をつける, to stake a claim) | `十年前につけた呪い` (0.999) | "the curse i put on you ten years ago" |
| rasetugari 039 | `気付け(きつけ)だ` | reading | `気付けだ` (1.0) | "healing hack" |
| rasetugari 052 | `平安の世より在藤(お前ら)に仕えて` | meaning: "the Arifuji" read as "you lot" | `平安の世より在藤に仕えて` (0.997) | "I've served the house of arifuji since the heian period" |
| rasetugari 052 | `千歳(ちとせ)の想いが` | reading | `千歳の想いが` (0.999) | "the millennium's worth affection" |

Two corrections to the issue's premises:

- **The 烏丸/からすま example is not ruby.** On bourei 003 the page prints the reading inline, in parentheses, in the caption (`烏丸　柩(からすま　くるる)　25歳`), and OpenMantra transcribes it the same way. The #350 note's "read with and without its furigana" was the OCR keeping or dropping printed parentheses. The replica on the original page keeps them: `烏丸・枢(からすまくるる)`. The same book prints `枢(くるる)` inline in dialogue on page 015, and the OCR keeps it there too (`どうぞ板(くるる)と呼んでください`, with 柩 misread).
- **The professional translators mostly followed the kanji, not the meaning ruby** (`妹(かずね)` → "his sister", `在藤(お前ら)` → "the house of arifuji", `呪い(つば)` → "the curse"). They did use every reading ruby for names. That sets a realistic bar: the reading ruby carries most of the practical value.

### Asides: examples

| Page | Layout | What Yomu does |
|---|---|---|
| tencho 010 | Small unbubbled `人通り多い時間に掃除させよ…` beside the shopkeeper, under his bubble `集客効果ありそう?` (his scheming, under his breath) | Own line `[8]`, placed after `[1] 集客効果ありそう?` and before the other speaker's `[4] また来てねー!` |
| tencho 012 | Small unbubbled `はい お金とメモ` next to the bubble `お金は少し多めに…` | Own line `[7]`, placed *before* its bubble `[1]` |
| tencho 029 | Unbubbled `指なくなっちゃう…` beside the bubble `できる気がしないよぉ…`, next to a third bubble `てんちょぉ…` | One box over all three: `でんちょお...できる気がしないよね...指なくなっちゃう...` |
| tencho 030 | Unbubbled `ふぉぉ…` beside `…できる気がしてきた…` | One box: `...できる気がしてきた...うおお...` |
| bourei 011 | Bubble `まぁいわゆる枕とか… 新人潰しとかですね`, small square box `あくまで噂ですが` ("just a rumour, mind you") | One box: `まぁいわゆる枕とか・・・新人達しとかですねあくまで噂ですが` |
| bourei 021 | Bubble `「妹のアンタが一番よくわかっているでしょう?」`, satellite `ですって` | One box: `…でしょう?」ですって` |
| bourei 026 | Small bubble `…頑張ったのにぃ` beside a panel of laughter | Own line `[1]` |
| bourei 009 | Bubble `すずめさんに自分のメイクをさせるな`, satellite `って` | Both missed: the detector scored them 0.10 and 0.06, under the 0.25 threshold (screentone background) |

When an aside is merged, its translation is painted over its parent bubble's box, which is roughly the right place, and the reply reads as one run-on line. When it has its own box, it is translated alone and painted where it is printed; the model sees it only as the next line in reading order. When the detector misses it, the reader sees the Japanese.

## 2. Line breaks

### 2a. Can the OCR stage keep them?

**Not from the model.** MangaOCR is trained to drop them:

- Synthetic training labels are built as `"\n".join(lines)` ([`generator.py:51`](https://github.com/kha-white/manga-ocr/blob/c333b5d36e88d539d6b040b4c4cf90ad5ecd4f69/manga_ocr_dev/synthetic_data_generator/generator.py#L51)) and tokenized with a BERT tokenizer ([`dataset.py:89-94`](https://github.com/kha-white/manga-ocr/blob/c333b5d36e88d539d6b040b4c4cf90ad5ecd4f69/manga_ocr_dev/training/dataset.py#L89-L94)), whose basic tokenizer treats a newline as whitespace and drops it. The vocabulary has no newline token (`vocab.txt`, 6,144 entries).
- The reference inference removes all whitespace (`"".join(text.split())`, [`ocr.py:60-61`](https://github.com/kha-white/manga-ocr/blob/c333b5d36e88d539d6b040b4c4cf90ad5ecd4f69/manga_ocr/ocr.py#L60-L61)).
- The 2025 weights Yomu ships come from [jzhang533's fork](https://github.com/jzhang533/manga-ocr), whose changes to the generator only swap `budou` for `budoux` (compare `kha-white:master...jzhang533:master`). Same labels, same behaviour.

**From geometry, yes, but reading per column costs accuracy.** mokuro, the best-known MangaOCR front end, reads per line: it takes comic-text-detector's line polygons, OCRs each line separately and stores `lines` per block ([`manga_page_ocr.py:64-87`](https://github.com/kha-white/mokuro/blob/9f79b1281066f953ec6e5d1e7086c401fa8da159/mokuro/manga_page_ocr.py#L64-L87)). I tried the same with Yomu's boxes and a projection-profile column split, on the 853 multi-column boxes that have one OpenMantra transcription each:

| Read | Character error rate vs OpenMantra | Exact reads |
|---|---|---|
| Whole box (today) | 4.9% | 715 / 853 |
| Per column, each cropped to its own ink | 6.6% | 687 / 853 |
| Per column, padded to at least 1:2 | 8.1% | 700 / 853 |
| Per column, rotated 90° | 117% | 0 / 853 |

Short columns were the problem: squashed to 224×224, `ちょっと` came back `ちょっとーーー`, and `てっ` came back `ててっ、`. Per-column reading also multiplies encoder passes by the column count (2.6 per multi-column box on average). A cheaper route keeps the whole-box read and only inserts break positions, by aligning the per-column reads to it; I did not build or measure that.

### 2b. Do the curated models translate better with breaks?

A little, and only with the right separator. An ideographic space at the breaks raised the content checks on six of eight models by 2–8 of 76, left two unchanged, and almost never malformed a reply; ` / ` broke 12–22 of 22 replies on every model ([§6](#6-desktop-llm-test-qwen25-15b), [§7](#7-the-24b-catalog-models)). Given 2a, the question only matters for the ≈12% of multi-column boxes whose break marks a boundary, and in those the joined text is still readable Japanese (`えっそうなんですか!?`).

## 3. Ruby

### 3a. Can it be told apart from the main text?

**By size and position, yes, on this corpus.** Ruby in vertical text is a narrow column immediately right of its base column. A projection-profile rule (a run 0.2–0.62 of the median column width, within 0.45 column widths right of a full-width column, at least 1.5× as tall as wide) proposed 51 of the 1,456 boxes; 19 were real ruby (precision 37%). Loosening the rule added 129 candidates and no new ruby. The false positives were small kana, `ッ`, `!!` and staggered short columns. The rule missed rasetugari 028, whose ruby sits partly outside the detector box; rasetugari 024's ruby is also cut by the box edge. A real ruby pass would have to widen the box before looking.

**MangaOCR drops it by design.** The synthetic generator adds random furigana to half the training images, but only after the label is fixed, so the label never contains it ([`generator.py:51,66-69`](https://github.com/kha-white/manga-ocr/blob/c333b5d36e88d539d6b040b4c4cf90ad5ecd4f69/manga_ocr_dev/synthetic_data_generator/generator.py#L51-L69); the `<ruby><rt>` markup is built in `add_random_furigana`, `:121-151`). The README lists "text with furigana" as a robustness goal, not an output. The model is doing what it was trained to do.

**A separate pass with the OCR Yomu already ships reads it well, once the crop is right.** I cropped every run the column finder flagged (all 51 boxes, so its precision shows too), padded each by a third of its width, and read it with the pinned MangaOCR ONNX, unchanged:

| Flagged boxes | Read exactly | Character error rate | Notes |
|---|---|---|---|
| 19 real ruby | 16 | 25% (driven by one miss) | `ありふじひろや`, `かきょうまる`, `ツミカクゴ`, `お前ら`, `せかい` all exact, confidence 0.91–1.0 |
| — misses | 3 | | tojime 003: the finder picked a main column, read `トルティヤーノないかと...`; rasetugari 024: the ruby is cut by the detector box, read `カマオ` for `かずね`; rasetugari 039: `きっけ` for `きつけ` |
| 32 false positives | — | — | Each read as the main text it really was: `ぶちまけて`, `ははっ`, `身長に比べ`, `まさか`, `お、おい......` |
| 1 real ruby not flagged | — | — | rasetugari 028 (`お前` beside `在藤`), outside the detector box |

The OCR's confidence does not separate the false positives (most score 0.9 or more). A filter on the read itself (2–8 kana, no punctuation) keeps 17 of the 19 real crops, two of them misread, and still keeps 8 of the 32 false ones, all short interjections such as `はっ` and `ふふ`; it also drops `お前ら` for its kanji. So the reading is solved and the finding is not: a shippable pass needs a better geometric test (for example, that the run sits beside a longer column whose glyphs line up with it) and a crop widened past the detector box. Which base kanji a ruby belongs to follows from its vertical position, because vertical text sets one glyph per column-width step; I did not build that step.

**Manga109, the data MangaOCR and most manga detectors train on, has no ruby or bubble class.** Its paper defines four box categories (frame, text, face, body) and a "text content" annotation entered as a Unicode string; it does not mention ruby ([Aizawa et al., IEEE MultiMedia 2020, arXiv 2005.04425](https://arxiv.org/abs/2005.04425), §Manga109 Annotations). [Manga109-v2026](https://arxiv.org/abs/2605.21182) revised about 29,000 dialogue transcriptions and does not mention it either.

### 3b. Do the models use marked ruby?

It depends on the model ([§6](#6-desktop-llm-test-qwen25-15b), [§7](#7-the-24b-catalog-models)).

- **The default Qwen2.5-1.5B does not:** it mostly translates the parentheses literally, as a romanized or kana label, or ignores the ruby.
- **Qwen3.5 2B and 4B, and Ministral 3 3B, behave the same way.** The explanation sentence made them bracket even more.
- **Gemma 4 E4B does:** with the explained notation it used the ruby in 18 of 32 lines, with no brackets at all, mostly for names (11 of 18 reading-ruby lines) and in 7 of 14 meaning-ruby lines.
- **Gemma 4 E2B, Hy-MT2 1.8B and Qwen3 4B 2507 are in between:** they use names about half the time and still bracket some lines.
- **No model** turned `銃(ツミ)と閉じ眼(カクゴ)`, `薬店(うち)` or `呪い(つば)` into the ruby's meaning.

The 8B and 9B entries are untested.

## 4. Asides

### 4a. Can a box be linked to its bubble from geometry alone?

**With Yomu's detector, no.** It has one class, so it cannot tell a satellite bubble or an unbubbled aside from a bubble, and in 24 of 62 satellite cases it has already merged the aside into the neighbour's box before any linking could run. ADR-0007 rejected deriving a class heuristically from box geometry, and that reasoning still holds for a guessed "aside" flag.

**With a bubble-aware detector, mostly yes.** `ogkalu/comic-text-and-bubble-detector` v4-s returns `bubble`, `text_bubble` and `text_free` boxes. On the 214 pages it returned 1,566 bubbles, 1,554 in-bubble texts and 73 free texts. Two simple rules found every aside listed in §1:

- a `bubble` within 15 px of one at least 2.5× its area is a satellite of that bubble (62 found);
- a `text_free` box touching a bubble is an aside candidate (25 found).

They also over-select. Most satellites are ordinary chained bubbles of one speaker (`あ?`, `チッ`, `え、ええと…`), and most `text_free` boxes are titles, credits, signs (`処分品セール`, `ASAGAO`), SFX or narration. Only 4 of the 73 `text_free` boxes were clearly asides. Geometry can find *attachment*; it cannot tell an under-breath remark from a continuation.

### 4b. How should the prompt mark it and the overlay render it?

**Prompt: put it next to its parent; do not add a mark.** The batch prompt already gives the model the whole page in order, and #350 found every kind of mark leaked into the reply (`(narration)`, `(male)`), because the grammar admits `(`. The useful change is order: an aside's line should follow its parent's line, which `ContextAssembler` gets wrong when the aside sits above or right of it (tencho 012). A mark was not tested here (the aside prompt arms were cut for time), so this rests on #350's leak finding.

**Overlay: in its own box, at its own place, smaller.** That is what Yomu already does when the aside has its own box, so no new renderer is needed. A merged aside is the case to fix, and the fix is detection, not rendering.

## 5. Existing models, and what training would take

| Model | Outputs | Size | Licence | Fits Yomu? |
|---|---|---|---|---|
| `Kiuyha/Manga-Bubble-YOLO` yolo26n (shipped) | `Text` boxes only | 6 MB | Apache-2.0 on the card, AGPL-3.0 base (ADR-0007, ADR-0019) | In use |
| [`ogkalu/comic-text-and-bubble-detector`](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/tree/16e8a622f91fabc6b5b65c96d32d1183f8843546) `detector-v4-s_int8.onnx` | `bubble`, `text_bubble`, `text_free` | 11 MB (int8); also 44 MB int8 and 168 MB fp32 RT-DETR-v2 r50vd | Apache-2.0 on the card; training set "about 11k Manga, Webtoon, Manhua and Western Comic style Images", not published | Candidate for asides (bubble class), not ruby or lines. Ran on desktop: 0.05 s/page |
| [comic-text-detector](https://github.com/dmMaze/comic-text-detector) (used by mokuro) | text blocks, text lines, text mask | 95 MB ONNX ([release](https://github.com/zyddnys/manga-image-translator/releases/tag/beta-0.2.1)) | GPL-3.0 code; trained on Manga109-s, DCM and synthetic data, sets not shared (README) | Gives lines. GPL-3.0 can be combined with AGPL-3.0 (ADR-0019), but 16× the current detector's size. Not run |
| MangaOCR / manga-ocr-2025 (shipped) | one string per crop | 134 MB ONNX | Apache-2.0 | Trained to drop ruby and breaks (§2a, §3a) |
| [FuriganaDetection](https://github.com/nikolajkb/FuriganaDetection) (Bjerregaard et al., [arXiv 2207.03960](https://arxiv.org/abs/2207.03960)) | furigana regions, by morphology and connected components, no learned model | — | No licence file in the repo (GitHub reports none) | Cannot be shipped as is; F1 76% on its own set, "less well on comics" (abstract) |
| Magi | panels, text, characters, speaker links | large | academic only | Rejected in ADR-0007 |

**Nothing shippable outputs ruby, and nothing phone-sized outputs line structure as a learned output.** ogkalu's detector is the only one with a bubble class.

**What training would take, if wanted.** Manga109-s is the obvious base, and ADR-0007 already notes its licence permits commercial use of results. It has no ruby or bubble-shape labels, so either kind would need new annotation. The synthetic route MangaOCR uses already renders ruby with `<ruby><rt>` (`generator.py:151`); changing the label to keep it, for example as `base(ruby)`, and fine-tuning the 2025 weights would teach the OCR to emit it inline. That is a training project (the fork's README reports ~15 epochs on Manga109-s plus 1.5 M synthetic images to match the original), and nothing in Yomu trains models today.

## 6. Desktop LLM test (Qwen2.5-1.5B)

**Setup.** Homebrew llama.cpp build 9430 (`d48a56e`) `llama-server --jinja -c 4096 -ngl 99` on the M4 Pro's GPU (Metal), with the app's pinned Qwen2.5-1.5B-Instruct Q4_K_M (`scripts/speed-fixtures/models/llm/qwen25_1.5b_instruct_q4_k_m.gguf`). Each prompt went through `/apply-template` as one user turn, as `chat_prompt.cpp` sends it, and then `/completion` with the grammar `buildBatchGrammar` builds and the app's default sampler (`GenerationParams()`: temperature 0.2, top-k 40, top-p 0.9, repeat penalty 1.0), seeds 7 and 8. The prompts are the shipped `buildBatchPrompt` text built from the replica's OCR and reading order for each whole page, so every edited line sits among the page's real lines. The emulator was not used. The 2–4B catalog entries follow in [§7](#7-the-24b-catalog-models).

### Ruby (15 pages, 17 ruby lines, 2 seeds = 34 per arm)

Arms: `none` (shipped), `paren` (`base(ruby)` inline, header unchanged), `paren_note` (the same plus a header sentence explaining the notation and asking not to copy the parentheses), `aozora` (Aozora Bunko's `｜base《ruby》` notation, header unchanged).

| Arm | Ruby shaped the English, no brackets | Ruby carried into the English in brackets | Ruby ignored | Other failure |
|---|---|---|---|---|
| `paren` | 3 | 18 | 13 | — |
| `paren_note` | 4 | 8 | 18 | 2 lines left untranslated (`俺は在隣(お前)をー`, both seeds); 2 invented pronunciations (`Wuché (pronounced "Woo-chee")`) |
| `aozora` | 4 | 4 (`'極格《ごくえん》'` copied whole, twice) | 9 | `\|` or `《》` leaked into 17 replies |

What the replies look like (seed 7 unless noted):

- `銃(ツミ)と閉じ眼(カクゴ)しかないというのに` → "I have only the gun (Tsumi) and the blindfold (Kakugo)." The meaning ruby is romanized and kept as a label, not translated as "sin" and "resolve".
- `閉じ目の闇(せかい)ですから` → "Because of the darkness of the closing eye (world)"; seed 8: "…closing door (seika)". The only meaning ruby that came through, once, as a gloss.
- `腹をこわして薬店(うち)にかけこみ` → "Hesitate to bring your stomach to the pharmacy (store) (うち)."
- `和音(かずね)を離せ!!` → "Let go of the harmony (かずね)!" in both seeds. The reading is copied in kana; the name "Kazune" never appears in any arm.
- `平安の世より在藤(お前ら)に仕えて` → "I am here to serve the court of Kyoto (your people)."; seed 8 copies `(お前ら)`.
- `華鏡丸(かきょうまる)` → "Kakiyo Maru" / "Kikyou Maru", against `none`'s "Kōkai Maru" / "Kurokaze Maru": the one case where the reading pulled the romanization closer.
- `在藤宏也(ありふじひろや)` → "In Ushihiroya (Ryūza)": the ruby made the name worse, not better.

**Answer to 3b, for the default model:** Qwen2.5-1.5B does not use ruby the way a reader does. Given `強敵(とも)`-style input it mostly translates the parentheses literally, as a romanized or kana label, and otherwise ignores the ruby. Explaining the notation halved the copying but did not make it use the meaning, and cost two untranslated lines. Aozora notation leaked its punctuation. The grammar admits all of these marks (`[^\r\n\[]`), so the reader would see them.

### Line breaks (11 pages, 11 lines, 2 seeds = 22 per arm)

Arms: `none` (shipped, columns joined), `space` (columns joined with an ideographic space `　`), `slash` (columns joined with ` / `, plus a header sentence).

- **`space` helped where the break separates an interjection, and hurt elsewhere.** Better: `チッ　今日は　帰ってやる` → "Tch, I'm going home today." (`none` dropped the "Tch"), `はっ　無駄だよ　レーネ` → "Huh, it's a waste, Rene.", `えっ　そうなん　ですか!?` → "What? So that's it!?" (`none`: "What?!"), `だが　あながち　間違いじゃない　あの眼` → "But, it's not entirely wrong. That eye" (`none`: "But that's not the case with that eye"). Worse: `まったく　マヌケは何を…` gained an invented "your father" in both seeds; `…通いたかったの　だけど` lost its "but". I count 8 lines better, 5 worse and 9 about the same, on 11 lines: not a result to build on.
- **`slash` broke the page.** `チッ / 今日は / 帰ってやる` became a line of 80 slashes in both seeds; `てっ / 思ったより / 窓が高かった...` came back untranslated as `/ 思ったより / 窓が高かった...`; most other replies kept the slashes, which the typesetter would draw.

**Answer to 2b:** an ideographic space at the column breaks is harmless to the reply's shape and helps the 1.5B model with interjection boundaries on this small set, but it also invites new errors, and getting the break positions needs the per-column work §2a found costly.

## 7. The 2–4B catalog models

Added in a follow-up run, finished 2026-11-02. Seven catalog entries ran through the same 15 ruby pages and 11 line-break pages, 2 seeds each, with the `aozora` arm dropped. Each used the exact GGUF the app pins (`ModelManager.kt` URLs, checked against their SHA-256), its own Jinja template with `enable_thinking` off as `chat_prompt.cpp` sets it, its catalog system message (Ministral only) and its catalog sampling (`LlmModelCatalog.kt`). Hy-MT2 is not a batch model (`idKeyedBatch = false`), so it got each edited line alone in Tencent's prompt (`hyMt2Prompt`), as `translatePerLine` sends it, with the explanation sentence inserted before its "Note that…" clause for the explained arms. Same build as §6 (Homebrew llama.cpp 9430, Metal).

**Scoring is automatic, so it is coarse.** Each ruby line falls in one bucket, checked in this order:

- **untranslated:** three or more Japanese characters outside brackets;
- **label:** any bracket, or the ruby's kana, in the English;
- **used:** the English contains the ruby's reading or meaning, for example `kazune`, `world`, `sin` or `resolv…`, `our shop`, `you`;
- **ignored:** none of these.

一家(ファミリー) is left out because the kanji and the ruby both say "family", so 16 lines × 2 seeds = 32 per arm. The `none` column shows how often the keyword appears with no ruby at all (千歳 is "a thousand years" either way); it is the floor to compare against.

For line breaks, each printed column has a regex for its content (`はっ` → `huh|hah|heh…`, `だけど` → `but|though`, 38 checks over the 11 lines, 76 per arm). The cell gives the checks met in well-formed replies, and in brackets the replies that were not well-formed: slashes or ideographic spaces kept, Japanese left in, 150+ characters, or empty.

<!-- per-model table: filled as each model finishes -->
| Model | Ruby, no ruby (`none`) used / label / ignored / untranslated | Ruby `base(ruby)` | Ruby `base(ruby)` + explanation | Breaks: content kept, `none` | `space` | `slash` |
|---|---|---|---|---|---|---|
| Qwen2.5 1.5B (default) | 3 / 0 / 29 / 0 | 2 / 18 / 12 / 0 | 4 / 10 / 16 / 2 | 50/76 (0) | 58/76 (0) | 9/76 (19) |
| Qwen3.5 2B | 6 / 3 / 21 / 2 | 4 / 23 / 5 / 0 | 3 / 23 / 4 / 2 | 42/76 (0) | 50/76 (0) | 11/76 (17) |
| Gemma 4 E2B | 5 / 0 / 27 / 0 | 12 / 13 / 7 / 0 | 11 / 7 / 13 / 1 | 59/76 (1) | 59/76 (2) | 4/76 (20) |
| Hy-MT2 1.8B (per line) | 4 / 0 / 28 / 0 | 12 / 4 / 16 / 0 | 8 / 17 / 7 / 0 | 60/76 (0) | 62/76 (0) | 8/76 (20) |
| Ministral 3 3B | 2 / 13 / 17 / 0 | 3 / 23 / 6 / 0 | 1 / 26 / 4 / 1 | 49/76 (0) | 55/76 (0) | 24/76 (12) |
| Qwen3.5 4B | 5 / 0 / 27 / 0 | 5 / 15 / 12 / 0 | 0 / 29 / 3 / 0 | 65/76 (0) | 65/76 (0) | 0/76 (22) |
| Qwen3 4B Instruct 2507 | 3 / 0 / 29 / 0 | 10 / 4 / 18 / 0 | 11 / 5 / 16 / 0 | 56/76 (0) | 63/76 (0) | 14/76 (18) |
| Gemma 4 E4B | 3 / 0 / 29 / 0 | 18 / 4 / 10 / 0 | 18 / 0 / 14 / 0 | 61/76 (0) | 67/76 (0) | 7/76 (20) |

Split by kind (bracket-free uses only, 18 reading-ruby and 14 meaning-ruby lines per arm):

| Model | `base(ruby)`: reading / meaning used | + explanation: reading / meaning used |
|---|---|---|
| Qwen2.5 1.5B (default) | 1 / 1 | 2 / 2 |
| Qwen3.5 2B | 2 / 2 | 2 / 1 |
| Gemma 4 E2B | 8 / 4 | 7 / 4 |
| Hy-MT2 1.8B | 8 / 4 | 6 / 2 |
| Ministral 3 3B | 1 / 2 | 1 / 0 |
| Qwen3.5 4B | 3 / 2 | 0 / 0 |
| Qwen3 4B Instruct 2507 | 6 / 4 | 8 / 3 |
| Gemma 4 E4B | 12 / 6 | 11 / 7 |

What the table shows:

- **Gemma 4 E4B is the only model that reads ruby the way a reader does.** With the explained notation it put no brackets in any reply and used the ruby in 18 of 32 lines. Seed 7 gave "Hiroya Arifuji", "Kureha", "Let go of Kazune!!", "Kakyōmaru", "The 'Gokuen' of Gohei…", "With illusion, my sister Kazune... Oh no!", "Serving you from the age of Heian" and "A world of darkness". It still missed the hardest meaning rubies: 銃(ツミ)と閉じ眼(カクゴ) came out as "only a gun and blind faith", 薬店(うち) as "the medicine shop" and 呪い(つば) as "a curse".
- **Gemma 4 E2B, Hy-MT2 and Qwen3 4B 2507 use reading ruby, mostly names, about a third to half of the time.** They still bracket a share of lines, for example Gemma 4 E2B's "a gun (tsumi) and blindfold (kakugo)" and Hy-MT2's "I am the neighbor (you)".
- **The Qwen3.5 models, Ministral and the default Qwen2.5 mostly turn ruby into labels.** For Qwen3.5 4B and Ministral the explanation made it worse: 29 and 26 bracketed lines out of 32. Ministral also brackets its own commentary even with no ruby in the prompt (13 lines in `none`), so its label count is partly its own style.
- **Line breaks:** joining columns with an ideographic space almost never broke a reply's shape: only Gemma 4 E2B had malformed replies, 2 of 22, against 1 with no spaces. It raised the content checks on six of the eight models, by 2 to 8 of 76 (Gemma 4 E4B 61 → 67, Qwen3 4B 2507 56 → 63), and left Gemma 4 E2B and Qwen3.5 4B unchanged. ` / ` broke 12 to 22 of 22 replies on every model.

## Recommendations

- **Line breaks: leave them alone for now.**
  - **Why not now:** most breaks are letterer wrapping, and MangaOCR cannot emit them. Recovering the break positions means a second OCR pass per column, and reading per column alone costs accuracy (§2a).
  - **What the evidence does support:** joining columns with an ideographic space is safe on every curated model and helps a little with interjections (§7). If column positions ever come cheaply, for example from a detector that outputs lines, sending them that way is a one-line prompt change. ` / ` must never be used.
- **Ruby: worth building only as a pair of changes, and only for models that use it. Deferred as #438 (`future`).**
  - **Reading is solved:** the shipped MangaOCR, run on a ruby crop, read 16 of the 19 real ones exactly (§3a).
  - **Finding is not:** the projection rule's candidates were 37% precise. A kana-only filter on the read still let 8 of 32 false ones through, and it dropped a real ruby written with kanji.
  - **The build, roughly:**
    1. widen the detector box by about half a column;
    2. find narrow runs right of a column whose glyph rows line up with that column;
    3. OCR each run and attach it to its base kanji by vertical position;
    4. send `base(ruby)` with the one-sentence explanation, but only to catalog entries that pass this test. Today that is Gemma 4 E4B, with Gemma 4 E2B, Hy-MT2 and Qwen3 4B 2507 as candidates.
  - **The default Qwen2.5-1.5B should keep receiving plain text,** because it turns ruby into visible bracketed labels.
  - **Scope:** it touches 1.4% of boxes, but those include the character names a reader sees on every page of a battle manga.
  - **Why deferred:** the finder is unsolved, and today the build helps one optional 4B model, which still missed the hardest meaning rubies.
- **Asides: do not mark them.** Evaluate `ogkalu/comic-text-and-bubble-detector` v4-s as a bubble-aware detector. Its bubble boxes split the merges that cause the visible harm. Its `text_free` class plus a touch rule also puts an aside's line right after its parent. That is a detector evaluation under ADR-0015, not a build: #437.

## Open questions / not run

- **The 8B and 9B entries** (Ministral 3 8B, Qwen3.5 9B, and the Ternary-Bonsai and uncensored variants) were not run. The script in §7 runs any of them with one `llama-server` start each.
- **The aside prompt test.** Arms were prepared for six pages (as shipped, aside moved after its parent, moved and marked with `*`) but not run.
- **A better ruby finder.** The alignment test proposed above, and widening the crop past the detector box, were not built. Nor was attaching a ruby to its base kanji.
- **The real app.** The quoted reads are the desktop replica's, at the dataset's resolution. A `scripts/page-report.py` run per page on the `Yomu_16G` emulator would give the app's own reads and prompts. Ruby is about half the main glyph size, so a screenshot that fills a 1280-wide screen should give a ruby pass more pixels, not fewer; unverified.
- **The alignment route for line breaks** (§2a): not built.
- **comic-text-detector:** not run.
- **Scoring in §7 is automatic,** by keyword and bracket checks, so single counts can be off by a line or two. The replies are kept with the experiment outputs, not in the repo.
- **Aside frequency** is a lower bound: I looked at every satellite and `text_free` box, but not at whole pages, so asides drawn inside a normal bubble's area or missed by both detectors are not counted.

## Sources

- Yomu code at `ba91c31`: paths and lines as cited above.
- MangaOCR: [kha-white/manga-ocr@c333b5d](https://github.com/kha-white/manga-ocr/tree/c333b5d36e88d539d6b040b4c4cf90ad5ecd4f69) (README, `manga_ocr/ocr.py`, `manga_ocr_dev/synthetic_data_generator/generator.py`, `manga_ocr_dev/training/dataset.py`); [jzhang533/manga-ocr](https://github.com/jzhang533/manga-ocr) fork diff; [jzhang533/manga-ocr-base-2025@1e64d5b](https://huggingface.co/jzhang533/manga-ocr-base-2025) (Apache-2.0, empty card); [artsaraiva/manga-ocr-2025-onnx-past@1518987](https://huggingface.co/artsaraiva/manga-ocr-2025-onnx-past).
- mokuro: [kha-white/mokuro@9f79b12](https://github.com/kha-white/mokuro/blob/9f79b1281066f953ec6e5d1e7086c401fa8da159/mokuro/manga_page_ocr.py), GPL-3.0.
- comic-text-detector: [dmMaze/comic-text-detector](https://github.com/dmMaze/comic-text-detector) README, GPL-3.0.
- ogkalu detector: [model card and files @16e8a62](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/tree/16e8a622f91fabc6b5b65c96d32d1183f8843546).
- Manga109: [arXiv 2005.04425](https://arxiv.org/abs/2005.04425); [Manga109-v2026, arXiv 2605.21182](https://arxiv.org/abs/2605.21182).
- Furigana detection: [arXiv 2207.03960](https://arxiv.org/abs/2207.03960); [nikolajkb/FuriganaDetection](https://github.com/nikolajkb/FuriganaDetection).
- OpenMantra: `vendor/open-mantra-dataset/README.md`, `LICENSE.md` (CC BY-NC 4.0); Hinami et al., [arXiv 2012.14271](https://arxiv.org/abs/2012.14271).
