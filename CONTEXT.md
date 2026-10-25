# Yomu

On-device manga translation for Android. A page is captured on manual trigger, its speech bubbles are detected and read, and the text is translated locally without leaving the device.

## Language

### Models

**Curated model**:
A model Yomu selects, hosts a download URL for, and supports. The only kind of model allowed in the detection and OCR slots, and the shipped default in the translation slot.
_Avoid_: bundled model, official model, default model

**Custom model**:
A GGUF the user supplies from their own storage for the translation slot, alongside its curated default. Permitted but explicitly unsupported, and labelled as such wherever its output appears.
_Avoid_: sideloaded model, user model, BYO model, third-party model

**Deliverable**:
One model at one quantization — the unit the curated catalog lists, gates on device fit, and downloads. Two quantizations of the same model are two deliverables, each with its own size, checksum and licence.
_Avoid_: model variant, build, quant entry, artefact

**Experimental deliverable**:
A curated [[deliverable]] added on desk research and not yet confirmed on the reference phone, shown with an Experimental tag so the reader knows Yomu has not tested it. A temporary state with three exits — confirmed, switched to per-line, or removed (ADR-0017); the default is never experimental.
_Avoid_: beta model, preview model, untested model

**Fit budget**:
The RAM a deliverable must fit inside to be offered on a given device — its file size plus a fixed resident-overhead estimate, against a fraction of the device's total memory. Estimated from measurement, never from parsing a file Yomu has not downloaded.
_Avoid_: RAM gate, memory limit, device tier

**Language label**:
English only or Multilingual, shown beside a [[deliverable]]'s size and [[speed label]] so a reader knows before downloading whether it can translate into their [[target language]]. Multilingual means the model's own documentation covers every target language Yomu offers; anything less is English only.
_Avoid_: language tier, language support level

**Model family**:
The set of deliverables that are the same model at different quantizations. Grouped only so device fit can offer the largest one that fits; a family is never itself selectable.
_Avoid_: quant matrix, model group, variant set

**Speed label**:
Fast, Medium or Slow, read off a [[deliverable]]'s download size alone (Fast up to 1.5 GB, Medium up to 3.5 GB, Slow above it), so the picker warns a reader how slow a model will feel before gigabytes are downloaded. The same thresholds on every device; how much RAM a deliverable needs is the [[fit budget]], a separate question.
_Avoid_: performance tier, speed rating, device tier

**Translation slot**:
The selected component that accepts one geometry-free page and owns every model-specific translation decision. Exactly one LLM deliverable fills it at a time; there is no non-LLM translator (ADR-0016).
_Avoid_: translation bridge, selected engine wrapper, translation engine

### Translation context

**Name glossary**:
The reader's own list of [[glossary entry|glossary entries]], one for every page and every series, so a name comes out the same on every page. An empty glossary changes nothing.
_Avoid_: dictionary, term base, translation memory

**Glossary entry**:
A Japanese *term* and the *rendering* that replaces it in a bubble's source text before the page is translated. One rendering per entry, written by the reader in the language they read, and used as written whatever the [[target language]].
_Avoid_: glossary rule, substitution, mapping

**Panel**:
A group of bubbles inferred to belong to the same comic frame. Used to order bubbles and to mark grouping inside the page prompt — never to split a page into multiple model calls.
_Avoid_: conversation block, chunk, frame

**Session context** (withdrawn):
The previous page's source/translation pairs, carried into the next page's prompt so pronouns, names, and register stay consistent across a reading session. ADR-0013 withdrew it on measurement — the production-shaped payload overflowed the prompt cap on 4 of 17 pages and no arm showed a quality gain — and the plumbing is deleted, not dormant. The term is kept here because the ADRs that decided and undid it still use it; nothing in the code does.
_Avoid_: history, memory, conversation history

### Reading

**Page recall**:
Showing an already translated page again, instantly, when the reader captures the same page in the same overlay session with the same [[deliverable]], [[target language]] and every other setting that shapes the result. Keyed on a fingerprint of the captured frame, with the status bar and the floating button left out, never on source lines.
_Avoid_: page cache, translation cache, page memory

**Page report**:
One page's evidence for a bug report: its capture, each bubble's OCR text, the model's raw reply, the model and settings used, and per-stage timings. It leaves the phone only through the share sheet, when the reader sends it.
_Avoid_: bug bundle, diagnostics dump, crash report

**Source language**:
The language a page is read in, fixed by the curated OCR model in the OCR slot: manga-ocr reads Japanese, and a PP-OCRv5 recognizer reads Chinese and English, Korean, Latin-script or Cyrillic-script text. A script family is never named in the prompt; the model reads the source as written.
_Avoid_: input language, original language

**Target language**:
The language the reader reads translations in. English is the default and the only target an English-only [[deliverable]] can produce.
_Avoid_: output language, destination language, locale

**Time to first bubble**:
How long a page takes from being handed to the pipeline until its first bubble's translation is ready to draw; capture time is excluded. Report-only, like every speed number (ADR-0015).
_Avoid_: latency, TTFB, first-token time

## Visual system

**Chrome**:
The app's own managed screens — Home, History, Settings — rendered in Compose. The surface the paper-mâché visual system governs.
_Avoid_: main UI, app screens, the app

**Overlay control**:
An interactive element drawn over live manga to operate translation — floating button, quick-settings popup, status toast. Governed by the paper-mâché visual system, but its legibility over arbitrary artwork is a constraint, not a cosmetic choice.
_Avoid_: overlay UI, HUD, widget

**Typeset bubble**:
The translated text laid into a bubble over the live page, coloured for legibility against the artwork underneath (today dark ink on a light fill). Outside the visual system: its appearance answers to readability, never to the paper aesthetic.
_Avoid_: translation overlay, rendered bubble, text box

**Paper surface**:
A visual layer in the [[chrome]] evoking cut or layered paper through warm colour, shape, border and offset shadow — never a photographic paper texture. The building block of the paper-mâché language.
_Avoid_: card, panel, texture
