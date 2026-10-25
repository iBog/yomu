# The detection slot offers ogkalu's RT-DETR text detector as Experimental

**Status:** accepted, 2026-10-25. Revises in part [ADR-0007](0007-bubble-text-detection-approach.md).

The detection slot gains a second deliverable: `ogkalu/comic-text-and-bubble-detector`'s `detector-v4-s_int8.onnx`, an RT-DETR-v2 model under Apache-2.0, 11 MB, pinned at revision `16e8a62`. The reader picks it in Settings like any other slot model. YOLO26n stays the default, so a fresh install keeps the behaviour Yomu has tested.

## Why

YOLO26n misses narration text in rectangular captions. On a reader's page with a boxed vertical monologue, it scored the caption 0.09, below the 0.10 floor the threshold slider allows. Lowering the threshold cannot recover the caption, and doing so would admit sound effects that score 0.11. The RT-DETR model found the same caption at 0.95 and every balloon YOLO26n found. It was trained on about 11k pages of manga, webtoons, manhua and Western comics, which is where captions are common.

ADR-0007 rejected an earlier model from this author, a YOLOv8m available only as `.pt`. That rejection does not cover this one. ADR-0007 also asked to revisit the decision if a multi-class detector with published weights appeared. This model has three classes: `bubble`, `text_bubble` and `text_free`.

## Consequences

- **Only the text classes become detections.** `text_bubble` and `text_free` play the role of YOLO26n's single `Text` class. `bubble` frames the whole balloon rather than its glyphs, so it is dropped. OCR cropping, NMS and render boxes therefore run unchanged.
- **The model is recognised by its signature, not by an id.** A session with an `orig_target_sizes` input is run as RT-DETR: the page is stretched to 640×640 and boxes return in page pixels. Any other session is run as YOLO.
- **The threshold slider is shared.** At 0.25, the RT-DETR text scores on the test pages sat at 0.59 or above, with nothing between 0.12 and 0.59. A per-model threshold is not needed yet.
- **It is Experimental under [ADR-0017](0017-experimental-curated-tier.md).** It has run on a desktop, not a phone. Its phone check runs `scripts/run-speed-benchmark.sh` and compares page reports, and then takes one of ADR-0017's three exits.
