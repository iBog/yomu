## Yomu Project — Agent Instructions

This file governs all AI agent behavior in this repository.

### Git Conventions

1. **worktrees.** All feature work happens in a separate git worktree, not the main checkout.
2. **branch per feature.** Branch name: `feat/<short-description>`. One branch, one purpose.
3. **commits.** Use Conventional Commits. Subject ≤50 chars. Body only when "why" isn't obvious. Example:
   ```
   feat: add bubble detection ONNX wrapper
   
   Uses YOLOv11 Nano via ONNX Runtime Mobile. Outputs bounding boxes with confidence scores.
   ```
4. **no force push.** Never rewrite pushed history.
5. **commit after every passing test.** Each green test = one commit.

### Issue and Branch Workflow

Work is tracked as plain GitHub issues on `artsaraiva/yomu`. **There are no phases and no
milestones** — the ten-phase roadmap sequence stopped matching how the work actually ordered itself
and has been scrapped. Do not assign a milestone, do not ask which phase owns a piece of work, and
do not use "that's phase N" as a reason to defer something.

| Situation | Required action |
|---|---|
| Any feature, bugfix, refactor, or research task | Create a real GitHub issue first → create a focused branch → PR closes the issue |
| Bug found during device testing or review | Create an issue before implementation |
| New follow-up discovered during a PR | Create a separate issue; do not expand the current PR unless the user explicitly approves it |
| Infrastructure work (CI, Dependabot, review, security) | Create an issue before implementation |
| Trivial typo or one-line documentation correction | Direct branch/PR is allowed; no issue required |

**Required sequence:**
1. Create an issue with the behavior and acceptance criteria.
2. Create one focused branch for that issue: `feat/<issue-number>-<short-description>` or `fix/<issue-number>-<short-description>`.
3. Keep the PR focused. Its body must open with a bare `Closes #<issue-number>`.
4. Self-review the PR diff, run required verification, then request review.

**Rules:**
- Issues track real unfinished work; do not create retrospective placeholder issues.
- One issue normally maps to one branch and one PR. Split unrelated work into separate issues/PRs.
- **The closing keyword must be bare: `Closes #44`.** GitHub does not parse a markdown-linked number, so `Resolves the [#44](https://github.com/artsaraiva/yomu/issues/44) decision` creates no link — the issue shows no PR beside it and nothing auto-closes. This silently cost four PRs their links (#37, #42, #45, #50). Prose may link an issue however it likes; the keyword line may not. Verify after opening:
  ```
  gh api graphql -f query='{repository(owner:"artsaraiva",name:"yomu"){issue(number:NN){closedByPullRequestsReferences(first:5,includeClosedPrs:true){nodes{number}}}}}'
  ```
  An empty list means unlinked. Editing the body fixes it even after the PR is merged.
- **Spikes publish a tag, never a branch.** Spike code is never merged, so its `spike/<issue>-…` branch stays local. To link the code from the issue's results comment, push only a tag: `git tag spike-<issue> && git push origin spike-<issue>`, then link `tree/spike-<issue>`. A pushed branch with no PR leaves GitHub's "Compare & pull request" banner behind, and deleting it later takes a manual step.
- Use label `future` only for work intentionally deferred beyond the current delivery window.

### GitHub Workflow

1. **Flow:** branch → commit → push → PR → review → merge to `main`
2. **PR review:** Self-review the diff (`git diff origin/main...HEAD`) before requesting human review.

### Automated Review and Security Scanning

- **CodeRabbit** (`.coderabbit.yaml`) no longer reviews PRs automatically; comment `@coderabbitai review` on a PR to request one. Its review is advice, not a gate: tests and the human owner decide. Fix a finding in the PR when it is a real defect; resolve the thread with a one-line reason when it is not. A valid finding outside the PR's scope becomes a new issue.
- **Dependabot** (`.github/dependabot.yml`) opens weekly grouped minor/patch PRs for Gradle and GitHub Actions and a monthly `ml/llama.cpp` submodule bump. Major updates arrive as separate PRs. Merge only when Android CI is green; a llama.cpp bump also needs an on-device translation check, since its C API changes often.
- **CodeQL** (`.github/workflows/codeql.yml`) scans Kotlin and C/C++ on PRs, pushes to `main`, and weekly. Findings appear under Security → Code scanning.

**Severity policy:**
- **Critical/high** (Dependabot alert or CodeQL): blocks merging the PR that introduces it and blocks any release while open. Fix it, or dismiss it in GitHub with a written reason (false positive, unreachable code, vendored path not compiled into the app).
- **Medium/low:** does not block. Open an issue if it is real.

**Remediation path:**
1. Dependency alert: merge the Dependabot security PR, or bump the version by hand if the PR fails CI.
2. `ml/llama.cpp` alert: bump the submodule to an upstream commit with the fix; if upstream has none, dismiss with the reachability reason or open an issue to patch around it.
3. CodeQL alert in our code: open a `fix/<issue>-…` branch; the alert closes when the fixing PR's scan no longer reports it.

### Device Testing

Use `adb` on a connected phone or emulator. Yomu's overlays (floating button, quick settings, drawn page) are their own windows, so a plain `uiautomator dump` misses them; use `uiautomator dump --windows`.

To check a pipeline or rendering change, run `scripts/page-report.py <page.jpg> <out-dir>` once on `main` and once on the branch, then `scripts/page-report.py --compare <before> <after>`. It translates one page in Google Photos and pulls its page report; `--help` lists what it needs.

**When to test on-device:**
- After overlay/service changes (can't be unit-tested)
- After UI changes (Compose layout, new screens)
- After pipeline changes that affect rendering
- When the user reports a visual bug

**When NOT to test on-device:**
- Pure logic changes (typesetter math, cache, parsing) — unit tests suffice
- Build/config changes — `assembleDebug` is enough

### Testing

1. **TDD.** Red → Green → Refactor. Always write the failing test first.
2. **Run the test before implementation to confirm it fails.** Run it after to confirm it passes.
3. **Speed benchmark on model changes.** A change to a model, the inference runtime (llama.cpp) or `LlmModelCatalog` runs `scripts/run-speed-benchmark.sh` on a phone or emulator and pastes the printed table in the PR, naming the device it ran on (emulator milliseconds are not phone numbers). The numbers are report-only; nothing gates on them.

Code and test-quality rules live in `CODING_STANDARDS.md`; review checks every diff against them.

### Yomu Project Context

- **Stack:** Kotlin, Jetpack Compose, Hilt, Room, ONNX Runtime, llama.cpp
- **Architecture:** Main app + foreground overlay service
- **Scope:** Japanese, Chinese, Korean, Latin-script and Cyrillic-script sources → English, Brazilian Portuguese, Spanish, French, German and Russian; single-page, Android, local-only, system-wide overlay
### When stuck

1. Read the relevant spec or plan doc first.
2. If a skill exists for the problem, load it.
3. Ask the user for clarification before guessing.

## Agent skills

### Issue tracker

GitHub (`artsaraiva/yomu`) via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context — `CONTEXT.md` + `docs/adr/` at repo root. See `docs/agents/domain.md`.

