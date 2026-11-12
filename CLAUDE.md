# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Git, issue/PR, review, security and device-testing conventions live in `AGENTS.md` and apply here in full:

@AGENTS.md

Code and test rules: `CODING_STANDARDS.md`. Domain vocabulary (deliverable, fit budget, translation slot,
page recall, page report…): `CONTEXT.md` — use its terms, not the synonyms it lists under _Avoid_.
Decisions behind models, thresholds and trade-offs: `docs/adr/`. Say so explicitly if a change contradicts one.

## Commands

Clone with `--recursive` (`ml/llama.cpp` is a submodule; `git submodule update --init --recursive` otherwise).
`local.properties` must set `sdk.dir`. JDK 17; NDK + CMake 3.22.1 are needed to build llama.cpp.

```bash
# What CI runs on a PR (single ABI so llama.cpp compiles once)
./gradlew detekt && scripts/check-code-standards.sh
./gradlew lint :core:test testDebugUnitTest assembleDebug -Pyomu.abis=arm64-v8a

# Single module / single test
./gradlew :pipeline:testDebugUnitTest --tests 'com.yomu.pipeline.translation.TranslationEngineTest'
./gradlew :core:test --tests 'com.yomu.core.ResultTest'   # :core is plain JVM: `test`, not `testDebugUnitTest`

./gradlew installDebug
./gradlew connectedAndroidTest                # instrumented, needs device/emulator
scripts/run-speed-benchmark.sh                # required for model / llama.cpp / LlmModelCatalog changes
scripts/page-report.py <page.jpg> <out-dir>   # run on main and on the branch, then --compare <before> <after>
```

`scripts/check-code-standards.sh` fails on any main-source `.kt` over 300 lines or containing `!!`.
Pre-existing breaches are in `config/code-standards-baseline.txt`; if you fix one, delete its line or the
check fails.

## Architecture

Four Gradle modules, dependencies flow one way: `app → pipeline → ml → core` (`app` and `pipeline` also
depend on `core` directly).

- **`core`** — pure Kotlin/JVM: shared types (`TargetLanguage`, `GlossaryEntry`, `Result`…), no Android.
- **`ml`** — inference runtimes. `OnnxRuntime` wraps ONNX Runtime for detection and OCR (with
  past-key-value decoding). `LlamaBridge` / `LlamaTranslationBridge` implement `TextGenerationBridge` over
  a JNI layer in `ml/src/main/cpp/` (`llama_jni.cpp`, `chat_prompt.*`, `grammar_sampler.*`,
  `prompt_budget.h`) built by CMake against the llama.cpp submodule.
- **`pipeline`** — `TranslationPipeline` runs one captured page through stages:
  `bubble` (YOLO detector) → `ocr` (manga-ocr per bubble crop) → `context` (panels + right-to-left reading
  order) → `translation` (`TranslationEngine`: the whole page in **one** LLM call, grammar-constrained,
  id-keyed output) → `typesetting` (fit text to each bubble). Progress, errors and the result come back
  through `PipelineCallback`; the pipeline is cancellable.
- **`app`** — Hilt + Room + Compose. `di/PipelineModule` assembles the pipeline. `service/OverlayService`
  is the foreground service that owns a reading session: `capture` (one `MediaProjection` frame on tap) →
  pipeline → `overlay` (floating button, status, drawn page — each its own window). `service/` also holds
  model management and slot selection, page recall (frame fingerprint cache) and page reports.
  `translation/` holds the user-facing stores (target language, generation profile, resource limits, name
  glossary) and `LlmModelCatalog`, the curated list of translation deliverables. `ui/` is the Compose app.

Design constraints that shape most changes:

- Everything runs on-device; no network after model download (ADR-0018). Models are downloaded on first
  launch, pinned to exact Hugging Face revisions (ADR-0014).
- The LLM is the only translator; there is no non-LLM fallback (ADR-0016). Panels order bubbles and mark
  grouping in the prompt but never split a page into multiple model calls.
- Speed numbers (benchmark, time to first bubble) are report-only; nothing gates on them (ADR-0015).
- App visuals follow `docs/design/paper-mache-visual-system.md`.
