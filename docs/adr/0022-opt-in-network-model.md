# Translation may run on a server the reader sets up; the phone stays the default

**Status:** accepted for this fork, 2026-11-12. Revises [ADR-0018](0018-translation-stays-on-the-phone.md), whose Considered Options rejected "a translator on the reader's own hardware ... reached over the local network ... for now".

Settings > Translation gets a **Network Model** beside the **On Device Model**. The reader gives a server address (an Ollama or llama.cpp server on their own network, or any OpenAI-compatible API), an optional API key and a model, and chooses which of the two translates. Nothing changes until they choose it: the on-device model is the default, and a fresh install never contacts a server.

## What it does

- **One request per page.** The page goes in a single chat request through `/v1/chat/completions`, with the same id-keyed prompt the on-device batch uses, and the reply is read as `[id] text` lines. A server has no grammar here, so a reply that skips or garbles an id leaves that bubble unanswered, as an unanswered on-device bubble is.
- **Models come from the server.** `GET /v1/models`, then Ollama's `/api/tags`. If the server answers but lists no models, the reader types the model's name. A refused key or an unreachable server is an error, not a reason to type a name.
- **Detection and OCR stay on the phone.** Only the text of each page leaves it.
- **The network model writes every target language.** The on-device model's English-only limit and fit budget do not apply while it is in use, and the setup screen does not ask for an on-device translation model.
- **Recall keys on the server and the model**, so a page translated by one is not shown for the other.

## What it costs, and why it is accepted

- **Page text leaves the phone.** The README's "nothing leaves your phone" is now true by default, not always. Settings says so on the section itself.
- **Cleartext traffic is allowed.** A server on a home network is `http://192.168.x.x`, and Android blocks that unless `usesCleartextTraffic` is set. Android cannot limit it to local addresses, so the setting is app-wide. Every model download still uses https URLs pinned to a revision.
- **The API key is stored in the app's private storage**, in a file of its own that backups and device transfers exclude. It is not encrypted at rest.
- **A second translation path to support**, which ADR-0018 named as the reason to refuse. It is kept small: one slot behind the same `TranslationSlot` interface, no other stage knows the difference.

## Not done

Streaming replies, per-bubble calls, and the sampler settings (they belong to the on-device model; the network request sends a fixed temperature of 0.2).
