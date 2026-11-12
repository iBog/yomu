# The shipped translation architecture is a grammar-constrained page-level batch call

**Status:** accepted, 2026-09-10. Decides [#138](https://github.com/artsaraiva/yomu/issues/138) on the [#137](https://github.com/artsaraiva/yomu/issues/137) spike's numbers. Amends [ADR-0002](0002-cross-panel-translation-context.md) and corrects [ADR-0010](0010-qwen-default-cat-demoted-to-floor.md).

> **Superseded in part by [ADR-0015](0015-quality-belongs-to-the-model.md) (2026-09-15).** The grammar-constrained page-level batch decision stands. Superseded: *The residue gap is adjudicated, not waived*, the *Falsification condition* under Consequences, and the adjudication in *Amendment (#203)* and *Amendment (#214)*. Each is marked where it appears. Quality is no longer gated, so none of these is a condition the shipped architecture must still meet.

> **Revised by [#193](https://github.com/artsaraiva/yomu/issues/193) (2026-09-13), deciding [#144](https://github.com/artsaraiva/yomu/issues/144).** The "switch is gated on `!idKeyedBatch`" clause under *A user-visible switch would otherwise go inert* is superseded: the "Use surrounding dialogue (experimental)" switch, the `capture_context` preference and `TranslationPromptMode.CAPTURE_CONTEXT` are deleted outright. The subsumption argument there is why — the batch call already carries the whole page. The line references in that paragraph describe the code as it stood on 2026-09-10.

> **Revised by [#203](https://github.com/artsaraiva/yomu/issues/203) (2026-09-14), adjudicating [#198](https://github.com/artsaraiva/yomu/issues/198)'s gate 1.** Two clauses under *Consequences* are superseded. *The architecture decision ships behind #139* no longer holds: the build shipped ([#149](https://github.com/artsaraiva/yomu/issues/149)), measured 0.027 residue on the reference phone, and ships at that number, adjudicated under *Amendment (#203)* below rather than waiting on a 0.000 re-run *(adjudication superseded by ADR-0015)*. *The grammar bounds line length* is no longer co-owned with #139: `repeat_penalty` stays 1.0, the bound stays 160, and whether it can relax belongs to [#214](https://github.com/artsaraiva/yomu/issues/214).

> **Revised by [#214](https://github.com/artsaraiva/yomu/issues/214) (2026-09-14).** Two claims in *Amendment (#203)* are corrected by quoted hit texts: that all four 0.027 hits are run-on, and that confabulated structure appeared only at `repeat_penalty = 1.1`. Three of the four are confabulated structure. The shipped grammar now excludes `[` from `line`, which makes that class unreachable. See *Amendment (#214)* below.

> **Revised by [#368](https://github.com/artsaraiva/yomu/issues/368) (2026-10-20).** Run-on past the last line had a structural cause: the grammar required a newline after the last line, which masked end-of-generation. That newline is now optional. See *Amendment (#368)* below.

Yomu translates a page in **one page-level, id-keyed call per capture**, with the output shape enforced **at sample time by a GBNF grammar** rather than checked after the fact. The curated default (Qwen2.5-1.5B-Instruct) carries `idKeyedBatch = true`. Per-line translation survives as a **slot strategy** — for the CAT-Translate-0.8b low-storage floor, which cannot produce id-keyed output, and as the fallback for a page whose prompt exceeds the native prompt cap — not as the default architecture.

**Cross-page session context is withdrawn.** It is not deferred and not dormant: the plumbing is deleted.

## What was shipping, and why that is the question

[ADR-0002](0002-cross-panel-translation-context.md) decided page-level. `LlmModelCatalog.DEFAULT` has carried `idKeyedBatch = false` since [#90](https://github.com/artsaraiva/yomu/issues/90) (`0fceeff`), so every capture has routed to `translatePerLine`, `sessionContext` has been read by nothing, and [#119](https://github.com/artsaraiva/yomu/issues/119) then ranked three *per-line* prompt modes and shipped one. That was an architecture reversal recorded nowhere. This ADR is where it goes on the record — and where it is undone.

## Evidence

[#137](https://github.com/artsaraiva/yomu/issues/137), four arms on the shipped Qwen2.5-1.5B Q4_K_M over the 17-page / 147-bubble [ADR-0004](0004-translation-eval-contract.md) corpus, sampler parameters held fixed across arms, scored by the unmodified ADR-0004 scorer (`docs/research/137-grammar-batch-spike.md`):

| arm | non-translation | JP residue | median ms/page | peak PSS |
|---|---|---|---|---|
| per-line (shipped) | 0.000 | **0.000** | 3228 | 2074 MiB |
| batch, no grammar | 0.000 | 0.014 | **2056** | 2111 MiB |
| batch + grammar | 0.000 | 0.014 | 2396 | 2127 MiB |
| batch + grammar + session context | 0.000 | **0.272** | 2767 (13 pages) | 2146 MiB |

Run on a Pixel_10_Pro emulator. The gate metrics are device-independent; the latency figures are emulator-relative and comparable only to each other.

## Considered Options

- **Keep per-line (status quo).** Rejected. It is the only arm that passes ADR-0004 as written, and that is the whole of its case. Its advantage is 2 bubbles out of 147, both of which are a *sampler* defect (below); its cost is 36% of page latency, paid on every capture, because per-line pays a fresh prompt prefill and a cleared KV cache for every bubble.

- **Page-level batch without a grammar.** Rejected. It is the fastest arm and scores the same residue as the grammar arm, but residue does not price what the grammar buys. Two of 17 pages under the unconstrained batch lost **every** bubble to a malformed id tag (`"Burial shop [id]"`, `"Are only me?"` — 0 translations parsed, the whole page rendered as untranslated Japanese source), and the grammar recovered both. A silently untranslated page is the worst user-visible failure this app has.

- **Batch behind a flag, per-line as the default.** Rejected. A flag is two code paths and two eval matrices, permanently.

- **Grammar-constrained page-level batch. (Chosen.)** 26% faster than per-line *with the grammar's cost included*, at equal memory, with the structural failure class made unreachable and the residue gap attributable to a knob nobody has turned.

## The residue gap is adjudicated, not waived

> **Superseded by [ADR-0015](0015-quality-belongs-to-the-model.md).**

ADR-0004 sets Japanese-residue rate 0 as a gate "hits adjudicated against the reference". Adjudicated here: both grammar-arm residue bubbles are the **final id of a page**, and both are the model continuing to write after the translation ends —

```
balloon-dense-dialogue [15] "That's right... [1] ただ空気いれた風船をいくら集めても浮くわけねーだろ!! ..."
tencho-p33             [6]  "Sorry...  (Mel)  (Tenzoku)  (Mel)  (Tenzoku) ..."
```

— with **no repetition penalty in the sampler**. That is [#139](https://github.com/artsaraiva/yomu/issues/139)'s decision, it is orthogonal to call shape, and it would appear on the per-line path too if per-line ever generated long enough to ramble. The gap is therefore not attributed to the page-level call and does not disqualify it.

This is not the first time the gate's literal reading has been set aside on adjudication: [ADR-0009](0009-selectable-translation-model-set.md) already found that "no model passes the gate (residue is never exactly 0), so 'pass the ADR-0004 gate' cannot be the promotion bar". ADR-0004 is **not** rewritten here; the reading it already carries is applied.

## Consequences

**The architecture decision ships behind #139, not with it.** This ADR decides; the build issue does not merge until #139 lands a repetition penalty and a re-run scores the batch arm at 0.000 residue. Deciding and shipping are separate acts, and this repository's recurring failure is a claim that ran ahead of its measurement.

**Falsification condition** *(superseded by [ADR-0015](0015-quality-belongs-to-the-model.md): speed benchmarks are report-only and revert nothing)*. The latency case rests on an emulator. The build's acceptance criterion is a reference-phone (SM-S911B) run of **grammar-batch against per-line**: if the median ms/page gap is under **15%**, this decision reverts to per-line. The comparison is against grammar-batch — the arm that actually ships — not against the bare 36% figure, which is for a build that does not include the grammar.

**The grammar is mandatory on the batch path.** No flag, no fallback to unconstrained batch. Measured cost: **0.518 ms/token**, ~10× the base sampler chain but **3.2% of wall-clock** next to decode; 3.9% of tokens need the grammar-first re-sample.

**The grammar bounds line length, and that bound is a knob.** With `line` unbounded the model ran to the token cap on 5 of 17 pages; bounding it to **160 characters** (≈2× the corpus's longest human reference, which maxes at 75, median 22) cut that to 0 of 17 and left the two clipped tails that are this decision's entire residue gap. The value is not pinned here: it is a build-time knob co-owned with #139, and a working repetition penalty may let it relax.

**Session context is dead by decision.** ADR-0002's cross-page session memory was measured against a model for the first time in #137 and failed: the production-shaped payload (`OverlayService`'s full previous page, both languages) exceeds the 512-token `n_batch` prompt cap on **4 of 17 pages** (670 / 660 / 550 / 710 tokens), each returning `""` → `null` → an empty `PageTranslation` → the whole page rendered untranslated with no user-visible error. Surviving pages were **slower** (2767 vs 2396 ms) with 4.7× the grammar rejection rate (18.5% vs 3.9%), and no arm showed a quality gain. Koharu corroborates independently: its `TranslationContext` is populated only under `#[cfg(test)]`, and its coherence comes entirely from putting the whole page in one call — which is what this decision restores. `OverlayService`'s carry-and-clear plumbing and the `sessionContext` parameter are **deleted** in the build issue. Five dead-on-arrival fields is enough; this one does not become the sixth by lingering.

**`n_batch = 512` stays open, and stops being urgent.** It remains the binding ceiling on prompt size ([#136](https://github.com/artsaraiva/yomu/issues/136)), and its compute-buffer cost has never been measured. With session context gone and a per-page fallback in place, raising it is an optimisation rather than a prerequisite.

**A page that overflows the prompt cap falls back to per-line, loudly.** The per-line path exists anyway for the 0.8b, so this is reuse, not new code. Splitting a page across two batch calls was rejected: it divides the ids and reintroduces exactly the cross-panel blindness the page-level call is bought to remove. Today's behaviour — an empty `PageTranslation` and a silently untranslated page — is not retained under any option.

**Per-line survives as a slot strategy, not as an architecture.** [ADR-0012](0012-page-translation-slot.md) already puts batch-versus-per-line inside the slot. CAT-Translate-0.8b keeps its bare model-card per-line form unchanged (ADR-0002's #71 amendment) and is **not** re-measured under a grammar: forcing the id shape on a model that echoes its source produces well-formed garbage, and residue is the only gate that would catch it. Whether a grammar should constrain the per-line path at all stays open.

**`idKeyedBatch` stays a per-model capability flag.** `LlmModelCatalog.DEFAULT` flips to `true`; nothing else changes. The flag is read by the slot ADR-0012 makes responsible for strategy, and it is the single line whose wrong value caused this ticket, so it stays visible and greppable rather than dissolving into a hierarchy.

**The post-hoc guards stay, examined.** `looksLikeNonTranslation` (`TranslationEngine.kt:11-21`) and its loop heuristic are applied at one call site, `TranslationEngine.kt:88`, over `output.byId` — both strategies feed through it. The grammar constrains **shape, not content**: `[0] I'm sorry, I can't help with that` is grammar-valid output, and so is the `(Mel) (Tenzoku) (Mel)` loop above. `parseIdKeyedTranslations`'s entire-line regex (`LlamaTranslationBridge.kt:157`) is not a guard at all — it is the parser. Only its silent multi-line *drop* becomes unreachable, and that is a behaviour, not a line of code. Nothing is retired.

**Four native changes are prerequisites of the build.** Three are pre-existing defects [#135](https://github.com/artsaraiva/yomu/issues/135) found: the redundant `llama_sampler_accept` at `llama_jni.cpp:260` (harmless only because all four current samplers declare `.accept = nullptr`, but it would double-advance grammar state); `llama_sampler_init_grammar`'s return passed unchecked to `llama_sampler_chain_add`, so a malformed GBNF SIGSEGVs — a segfault-class fix that needs no architecture decision and can land independently; and the grammar sampler held **outside** `g_sampler` with upstream's rejection-sampling shape, because `llama_grammar_apply_impl` writes `-INFINITY` without clearing `cur_p->sorted` while `top_k` leaves it `true`, yielding NaN. The fourth is a behaviour change: **`MAX_BATCH_OUTPUT = 768`** caps the batch token budget, which un-inverts #136's self-refusal (the old "all of `N_CTX` the prompt does not use" budget is passed to `prompt_fits` as `output` and therefore *shrinks* the prompt allowance). `LLAMA_BUILD_COMMON` stays `OFF`; the GBNF is hand-written and two rules regardless of bubble count.

**A user-visible switch would otherwise go inert.** `promptMode` is read **only** inside `translatePerLine` (`LlamaTranslationBridge.kt:86`), so flipping the default to batch makes #119's shipped `TRANSLATION_ONLY` unread for the default model. Worse, `SettingsScreen.kt:97` renders "Use surrounding dialogue (experimental)" whenever the selected model's `promptMode == TRANSLATION_ONLY` — which stays true — so the switch would render and do nothing. That is precisely the pattern [#143](https://github.com/artsaraiva/yomu/issues/143) ruled must not be copied from koharu: on a phone the user cannot see that a switch did nothing. The switch is gated on `!idKeyedBatch`. The batch call **subsumes** capture-context anyway: it carries the whole page, not ±1 neighbour.

**The batch prompt is unvalidated and stays open.** `buildBatchPrompt` predates #119, and #119's three-mode ranking was per-line only — so the prompt now shipping on the winning architecture has never been benchmarked on it. It is a measurement, not a decision, and it carries a second question: with the grammar enforcing shape, does the prose instruction still earn its tokens against a 512-token prompt cap? Tracked separately.

**Bubble coverage cannot fail, and this decision rests on residue alone.** #137 measured coverage at 100% in all four arms *including* the one where 4 of 17 pages were never translated, because `TranslationEngine` substitutes `bubble.sourceText` for any id the slot does not return. Residue caught it only because the source happened to be Japanese; an ML Kit or OPUS-MT floor arm, or a change of source language, would not trip it. The coverage column in the table above is reported to say so. Owned by [#142](https://github.com/artsaraiva/yomu/issues/142).

**What this re-arms elsewhere** *(superseded by [ADR-0015](0015-quality-belongs-to-the-model.md): ADR-0006's gate is retired and nothing here is measured)*. [ADR-0006](0006-coherence-gate-contract.md)'s contrastive gate was blocked — with `sessionContext` reaching no model, its directional test scores 0 delta by construction. It becomes runnable, but **narrowed**: the model now sees a whole page at once, so the gate can measure *intra-page* coherence only; *cross-page* coherence is withdrawn with session context and is not coming back. ADR-0006 is not redesigned here. [ADR-0008](0008-translation-model-selection.md)'s fine-tune trigger, re-anchored to Qwen by ADR-0010, stays **armed and unfired**: its antecedent is now evaluable rather than blocked, and [#145](https://github.com/artsaraiva/yomu/issues/145) is what evaluates it.

## Amends ADR-0002

Page-level, id addressing, per-bubble fallback, panel markers and empty-OCR omission are **reaffirmed on measurement** — the #68 and #71 amendments narrowed them to the 0.8b's limits, and on the current default they hold. The **session-memory limb is withdrawn**. ADR-0002 keeps its `accepted` status and carries a pointer; it is not superseded, because most of it just won its argument.

## Corrects ADR-0010

ADR-0010 states that Qwen's 0.102 residue "was scored through the ADR-0004 page-level id-keyed batch path with `supportsIdKeyedBatch() = true` — **the same call the app ships**". The final clause is false: the app has never shipped that configuration. The number's provenance is a page-level path the app did not run.

**The conclusion survives.** ADR-0010's comparison was arm-consistent — Qwen and the 0.8b were measured on the same page-level path — so the ranking that demoted the 0.8b holds, and #137 strengthens it (the same model measures 0.000 residue per-line and 0.014 batch on this corpus, both far below the 0.388 that demoted the floor).

**One number is genuinely unknown:** CAT-Translate-0.8b has **never been scored per-line on the ADR-0004 corpus**. Its 0.03 per-line residue is #68's, on a different corpus. The floor's number on the shipped harness does not exist.

## Amendment (#203): the shipped arm's residue is adjudicated, and the grammar's remaining lever is `line`

> **Adjudication superseded by [ADR-0015](0015-quality-belongs-to-the-model.md).** The shipped grammar and the 160-character bound stand.

**Grammar-batch ships at 0.027 Japanese residue.** [#198](https://github.com/artsaraiva/yomu/issues/198) measured it on the reference phone (SM-S911B) at `repeat_penalty = 1.0`, reproduced to the last digit across two runs. All four hits are one class, and it is the class this ADR already adjudicated at 0.014 above: **run-on past the translation.** The model finishes the real translation, keeps writing inside the same line, and the 160-character `line` bound clips it with Japanese in the tail. Under [ADR-0004](0004-translation-eval-contract.md)'s #203 amendment an adjudicated class passes, so the gate is met as it now reads, not waived.

**The four hit texts were not retained.** #198's run records did not survive, so this adjudication rests on #198's description of the hits rather than on quoted text. The two emulator hits quoted above are the same class.

**The grammar already pins the page's id count.** #203 proposed a grammar that fixes the number of ids per page, koharu's `minItems == maxItems == n` shape. That shipped with this ADR: `buildBatchGrammar` emits every real id as a literal, in prompt order, exactly once. Every residue hit is text **inside** `line ::= [^\r\n]{1,160}`, so the only lever the grammar has left is what `line` admits.

**`line` may exclude structure characters, never content characters.** Excluding `[` — which only an id tag needs — stays inside the "structural only" rule above. Excluding Japanese characters does not: it scores residue 0 by construction and disables the gate (ADR-0004). [#214](https://github.com/artsaraiva/yomu/issues/214) builds and measures `[^\r\n\[]`.

**Confabulated structure is not adjudicated.** The two hits that survived `repeat_penalty = 1.1` are a different class — single-bubble pages where the model writes fake id tags inside one line to keep talking:

```
bourei-sparse-single: Funeral home [id=1] お前、葬儀屋さんだな。 [id=2] That's a funeral home...
tojime-sparse-title:  Sealed Eye of Sila (Sila is a name) [id=1] [1] 朽鷹みつき
```

1.1 does not ship, so this class carries no verdict. A hit in it on the shipped arm fails the gate until it is adjudicated or #214 makes it unreachable.

**`repeat_penalty` stays 1.0.** 1.1 halves residue but costs 1.46 chrF2 (27.90 → 26.44) and moves readability 1.306 → 1.559, and a repetition penalty is the wrong instrument for the class it leaves behind. Recorded on [#139](https://github.com/artsaraiva/yomu/issues/139). [ADR-0014](0014-quantization-deliverables-and-revision-pinning.md) is unchanged: no generation parameter moves.

**The 160-character bound stays.** Unbounded, the model ran to the token cap on 5 of 17 pages. Whether it can relax once `[` is excluded is #214's measurement, not this amendment's.

## Amendment (#214): the 0.027 hits were mostly confabulated structure, and `line` now excludes `[`

> **Adjudication superseded by [ADR-0015](0015-quality-belongs-to-the-model.md).** The `[` exclusion stands; the rerun command below names eval code being deleted.

**The #203 amendment misclassified the shipped arm's hits.** #214 ran the pre-#214 grammar (`line ::= [^\r\n]{1,160}`) as a paired control on the reference phone (SM-S911B), at `repeat_penalty = 1.0` with the seed pinned. It reproduced #198's published numbers exactly: residue 0.027, chrF2 27.90, readability 1.306. This time the hit texts were retained. **Three of the four hits are confabulated structure. One is run-on.** Every hit is on the final id of its page:

```
balloon-dense-dialogue [15]  That's right... [1] ただ空気いれた風船をいくら集めても浮くわけねーだろ!! [2] こいつ絶対バカだし …
bourei-sparse-single [0]     Funeral home [id] 1 [0] お前、何をしたんだ？ [id] 2 [0] お前、何をしたんだ？ …
tojime-narration [2]         He blinded their eyes and sewed up their eyelids to force them out of the city.  [id=5] こいつだってのか...? …
bourei-p30 [4]               It's obvious!  (This is a typo, it should be "当たる" which means "to hit" or "to be hit". …   ← run-on
```

The #203 adjudication rested on #198's description because the texts were not retained. That description was wrong. Confabulated structure is not specific to 1.1: at 1.0 it was the majority of the shipped arm's residue. Read as ADR-0004's #203 amendment now reads, the gate was **not met** at 0.027, because three hits fell in a class with no verdict.

**The gate is met by making the class unreachable, not by adjudicating it.** #214 shipped `line ::= [^\r\n\[]{1,160}` (`GenerationParams.lineExcludesIdBracket = true`). On the same run:

| | `[` admitted (pre-#214) | `[` excluded (shipped) |
|---|---|---|
| Japanese residue | 0.027 (3 confabulated structure, 1 run-on) | 0.014 (2 run-on) |
| Output lines containing `[` | 5 | 0 |
| Lines clipped at the 160 bound | 7 | 4 |
| Mean chrF2 | 27.90 | 28.11 |
| Readability ratio | 1.306 | 1.261 |
| Coverage / shape | 100% / pass | 100% / pass |
| Median page latency | 13.8 s | 11.7 s |

Both remaining hits are run-on past the translation, the class adjudicated above: `bourei-p30 [4]` (the same text as the control's) and `bourei-p04 [1]` (`…good luck.  (Translation of "吉良いと" as "good luck")  (Note: …`). No unadjudicated class remains, so the shipped arm meets the gate at 0.014. None of the 17 ADR-0004 references contain `[`, so the exclusion costs the corpus nothing.

**Confabulated structure stays unadjudicated.** It is unreachable under the shipped grammar and carries no verdict. If the exclusion is ever reverted, its hits fail the gate again.

**The 160-character bound stays.** Excluding `[` removes only the tag-shaped form of writing past the translation. The model still runs on as parenthetical commentary or a punctuation loop, and 4 of 17 pages still reach the bound.

**The comparison can be rerun.** `eval/run-benchmark.sh --bracket-exclusion` pairs `grammar_bracket` with `grammar_no_bracket`. Both arms declare `gen.line_excludes_id_bracket`, and the scorer checks it against what the device recorded.

## Amendment (#368): the last line's newline is optional

**Run-on on the final id was the grammar's doing.** The reply grammar ended with `"[last] " line "\n"`, so end-of-generation stayed masked until that newline. Qwen2.5-1.5B ends a reply with end-of-generation, not a newline. With that token masked, it kept writing until the 160-character bound. On `balloon-dense-dialogue`, after `[6] That's right...` the unconstrained model put 0.998 on end-of-generation. The grammar forced its next pick, ` (`, which the model gave under 0.001. That explains why every run-on hit in the amendments above sat on the final id: only the last line's end is masked. It was not a missing repetition penalty.

**The fix: the last newline is optional, so the model can end its reply right after the translation.** The native loop now stops on any end-of-generation token (`llama_vocab_is_eog`), not only the vocab's EOS, and reports that the model ended the reply. Only then does the batch path accept a last line with no newline. A line the deadline or the token cap cut off still stays unanswered.

**Measured on the three fixture pages** (#350's real-OCR prompts on desktop, seeds 7/8/9/42; three speed-benchmark runs on the emulator, random seeds):

- Desktop: the last line ended with its translation in 12 of 12 runs, against 5 of 12 before. The earlier lines were byte-identical.
- Two other candidates lost. Excluding `(` from `line` kept the run-on as `/That's right...` loops. A line bound scaled to the source length still showed invented text, only shorter.
- Emulator: page decode time fell from 3,104 to 2,596 ms (`balloon-dense-dialogue`) and from 3,018 to 2,514 ms (`tojime-dense-action`). `bourei-p04` did not run on and stayed level (1,446 to 1,423 ms).

**The 160-character bound stays** as a backstop for a line that loops mid-page.

> **Amendment (#444 follow-up): the line bound scales with a long source line.** The 160-character bound was tuned on manga, whose longest source line is 75 characters. A visual-novel paragraph of 225 characters translated into Russian cannot fit in 160, so the grammar cut the translation off mid-sentence. The bound is now `max(160, 2 × the longest source line of the page)`: every page whose longest line is under 80 characters, which includes the whole fixture corpus, gets exactly the 160 this ADR measured. #214's "line bound scaled to the source length" candidate lost on that corpus by still inventing text, so this is deliberately not a general scaling: it only lifts the backstop where a 160 cut is certain to lose real translation. Whether a long line still runs on past its translation is unmeasured.
