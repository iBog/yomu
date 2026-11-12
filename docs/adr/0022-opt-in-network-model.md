# Translation may run on a server the reader sets up; the phone stays the default

**Status:** accepted for this fork, 2026-11-12. Revises [ADR-0018](0018-translation-stays-on-the-phone.md), whose Considered Options rejected "a translator on the reader's own hardware ... reached over the local network ... for now".

Settings > Providers holds the **On Device Model** and a **Network Model** beside it. The reader gives a server address (an Ollama or llama.cpp server on their own network, or any OpenAI-compatible API), an optional API key and a model, and chooses which of the two translates. Nothing changes until they choose it: the on-device model is the default, and a fresh install never contacts a server.

## What it does

- **One request per page, streamed.** The page goes in a single chat request through `/v1/chat/completions`, with the same id-keyed prompt the on-device batch uses, and the reply is read as it streams, so each `[id] text` line is reported as soon as it is whole. A server has no grammar here, so a reply that skips or garbles an id is followed by one more request for those ids alone (each as its own line); what that misses stays unanswered, and a failed retry keeps what the first reply gave.
- **Models come from the server.** `GET /v1/models`, then Ollama's `/api/tags`. If the server answers but lists no models, the reader types the model's name. A refused key or an unreachable server is an error, not a reason to type a name.
- **The model stays loaded, on Ollama only.** A chat request resets Ollama's unload timer to the server default of five minutes. An Ollama server is recognised once by `/api/version` and asked through its own `/api/chat`, which takes `keep_alive` (30 minutes by default, 5 minutes to 4 hours in Settings) in the same request, so there is no second request. Every other server keeps the OpenAI-compatible request and is sent nothing it does not know. This is written against Ollama's documented API and has not been run against a real server.
- **Detection and OCR stay on the phone.** Only the text of each page leaves it.
- **The network model writes every target language.** The on-device model's English-only limit and fit budget do not apply while it is in use, and the setup screen does not ask for an on-device translation model.
- **Recall keys on the server and the model**, so a page translated by one is not shown for the other.

## What it costs, and why it is accepted

- **Page text leaves the phone.** The README's "nothing leaves your phone" is now true by default, not always. Settings says so on the section itself.
- **Cleartext traffic is allowed.** A server on a home network is `http://192.168.x.x`, and Android blocks that unless `usesCleartextTraffic` is set. Android cannot limit it to local addresses, so the setting is app-wide. Every model download still uses https URLs pinned to a revision.
- **The address, the API key, the model and its keep-alive time are stored encrypted**, in `EncryptedSharedPreferences` (AES-256, key in the Android Keystore), in a file that backups and device transfers exclude, since it cannot be read without the Keystore key. Which engine translates is not a secret and stays in the plain preferences. Settings kept in plain files by earlier builds are moved on the first start and removed. If the Keystore cannot be used at all, they fall back to a private unencrypted file and the app logs it, rather than crash.
- **A second translation path to support**, which ADR-0018 named as the reason to refuse. It is kept small: one slot behind the same `TranslationSlot` interface, no other stage knows the difference.

## Not done

Per-bubble calls, structured (JSON-schema) output, and the sampler settings (they belong to the on-device model; the network request sends a fixed temperature of 0.2).
