# Yomu detects text with the ogkalu RT-DETR v4-s

> **Fork note.** This is upstream's ADR, renumbered because this fork already has [ADR-0020](0020-selectable-text-detector.md). Here the RT-DETR v4-s is the Experimental, selectable entry beside YOLO26n, which stays the default, so the replacement and the migration below are not applied. The `text_free` floor of 0.5 and the Latin-majority rule for Japanese reads are applied.

**Status:** accepted, 2026-11-12. Decides [#442](https://github.com/artsaraiva/yomu/issues/442) on the evidence of spike [#437](https://github.com/artsaraiva/yomu/issues/437). Revises [ADR-0007](0007-bubble-text-detection-approach.md) under its own clause: "revisit only if a multi-class manga detector with published weights appears".

The detection slot downloads `detector-v4-s_int8.onnx` from [`ogkalu/comic-text-and-bubble-detector`](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/tree/16e8a622f91fabc6b5b65c96d32d1183f8843546) at revision `16e8a62` (11,120,765 bytes) in place of `Kiuyha/Manga-Bubble-YOLO` yolo26n. It is an int8 RT-DETR-v2 with a ResNet-50-vd backbone and three classes: `bubble` (the balloon), `text_bubble` (text inside a balloon) and `text_free` (text outside one).

## What was looked at

[ADR-0015](0015-quality-belongs-to-the-model.md) asks for licence, size, speed and a look at output on real pages. The #437 results comment has the full numbers, and its code is on tag `spike-437`.

- **Boxes.** On OpenMantra's 214 Japanese pages it boxed 1,590 of the 1,592 annotated text boxes, against 1,538 for yolo26n. Only one of its boxes covered more than one annotated box, against 106 of yolo26n's, and it found all 33 boxes under 60 px tall, against 27.
- **Pages.** On the emulator, the asides yolo26n merged into their neighbours on bourei 011, bourei 021 and tencho 029 (`あくまで噂ですが`, `ですって`, `指なくなっちゃう…`) are read and drawn as their own lines.
- **Speed.** The detection stage took 64–92 ms per page in #437's page reports, against 63–83 ms for yolo26n, and 76–81 ms on the three fixture pages in #442's speed benchmark. All of these are emulator numbers. The model's input is a fixed 640×640, where yolo26n's grew with the page. OCR time grows with the extra boxes, and that is the real cost.
- **Size.** 11 MB, against 6 MB.

## Licence chain

Every link is Apache-2.0, and there is no AGPL anywhere:

- the model card and the Hugging Face API at `16e8a62`;
- `config.json`'s `RTDetrV2ForObjectDetection`, from transformers 4.49;
- RT-DETR upstream, `lyuwenyu/RT-DETR`;
- the ResNet-50-vd backbone, from PaddleClas.

The training set (about 11k manga, webtoon, manhua and western comic images) is not published. The same is true of yolo26n and manga-ocr-2025. [ADR-0019](0019-yomu-is-agpl-open-source.md) treats each model as a separate work at a pinned revision under its own licence, and [ADR-0017](0017-experimental-curated-tier.md)'s Apache-2.0-or-MIT rule is met. The remaining risk is where the training images came from, and switching detectors does not change it. ADR-0019's decision itself stands, since the detector was one of its reasons and not the only one.

## How it runs

- **A plain stretch to 640×640, RGB/255, with no mean or std.** That is the shape the model was trained on. The letterbox that #369 added for yolo26n goes with it: it would shrink a 1280×2856 capture to 287 px wide inside the input, while the stretched runs found every small bubble. `orig_target_sizes` is `[width, height]`; the other order puts boxes past the page edge.
- **`text_bubble` and `text_free` are text, and `bubble` is dropped.** The class only decides which boxes are kept. It never reaches the translation prompt, so ADR-0007's "no per-box class label" still holds there. Whether `bubble` boxes should shape render boxes is left open.
- **`text_free` has a floor of 0.5, whatever the reader's threshold.** Below it, the class boxes sound effects and the viewing app's own interface. At 0.5 its OpenMantra extras (21) fall below yolo26n's (25) while it still finds 1,589 of 1,592. `text_bubble` follows the reader's threshold, and the 0.25 default stands.
- **A read with more Latin letters than kana and kanji is not translated.** The floor does not stop Google Photos' Share label, which the model scores 0.66–0.70 as `text_free`. OCR reads the share icon as `ぺ`, so `ぺ。Share` passed [#364](https://github.com/artsaraiva/yomu/issues/364)'s rule, which drops only reads with no Japanese script. None of OpenMantra's 1,592 lines is Latin-majority.
- **NMS stays.** The model emits duplicates: one emulator page logged 24 raw boxes and 19 kept.

## Considered Options

- **Keep yolo26n.** Rejected. It misses 54 annotated boxes at 0.25 (the RT-DETR finds 53 of them), merges 106, and rests on an AGPL-3.0 base.
- **RT-DETR with `text_bubble` only.** Rejected. It finds 1,556 of 1,592, because captions and asides outside a balloon are `text_free`.
- **RT-DETR with both text classes at the reader's threshold.** Rejected. It keeps 71 extras on OpenMantra at 0.25, mostly sound effects and signs, and boxes the viewer's toolbar on the emulator.
- **A higher `text_free` floor instead of the Latin rule.** Rejected. Clearing the Share label takes a floor of about 0.75, which finds 1,581 of 1,592 and leaves a margin of 0.05 over the scores seen on one emulator.
- **A letterbox input.** No difference that matters (the same hits, 60 extras against 71), so the trained shape wins.

## Consequences

- A fresh install downloads the RT-DETR. On upgrade, the new model id makes `refreshModelList` drop the yolo26n row and its file, and setup asks for the 11 MB download once.
- The YOLO letterbox and output parser are deleted, since no YOLO model is left to run.
- ADR-0007's swap rule and containment numbers stay as history. Under ADR-0015 they are not a bar this choice had to clear.
