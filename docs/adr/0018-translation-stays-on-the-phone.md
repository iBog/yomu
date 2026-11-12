# Translation stays on the phone; cloud is parked, not planned

> **Revised in this fork by [ADR-0022](0022-opt-in-network-model.md).** An opt-in Network Model can translate on a server the reader sets up. The phone stays the default.

**Status:** accepted, 2026-09-26. Decides [#341](https://github.com/artsaraiva/yomu/issues/341), recording the decisions of spec [#329](https://github.com/artsaraiva/yomu/issues/329). Revises in part [ADR-0001](0001-custom-model-permissiveness.md).

Every stage of a page, from capture through typesetting, runs on the reader's phone. Yomu has no cloud translator, no server of its own, and no translator on another machine the reader owns. The network is used only to download curated models, and a page report leaves the phone only when the reader sends it through the share sheet. `product-spec.md`'s Cloud Boost modes (Local Only, Hybrid, Best Quality) and its credit-based pricing tiers are no longer the plan. They move to [#324](https://github.com/artsaraiva/yomu/issues/324), a `research` issue about monetization ideas, and the spec points here.

## Why

The README promises "Nothing leaves your phone", and the spec described a cloud fallback and paid cloud credits. Only one of them can be true. The phone-only promise is what separates Yomu from every cloud manga translator, and it is already what the app does. A cloud path would bring accounts, API keys, a server, billing and a second translation path to support and debug. The phone-only default (Qwen2.5-1.5B, ADR-0010) is good enough that this cost buys less than it did when the spec was written.

## Considered Options

- **Keep the spec's Cloud Boost and pricing as the plan.** Rejected. It contradicts the README and the shipped app.
- **Allow a translator on the reader's own hardware**, such as an OpenAI-compatible server on their PC, reached over the local network. Rejected for now. Page text still leaves the phone, so the README's promise would need rewording, and it is the same second translation path to support.
- **Phone only, with cloud parked behind a named condition. (Chosen.)**

## Reopening

Cloud translation is parked, not ruled out. The condition that reopens it is a Play Store launch with paid features. Reopening takes a new ADR that supersedes this one, and the README's promise changes in the same pull request.
