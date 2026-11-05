# Internal tooling guide

Product development is HOLD until the user explicitly resumes it. Finishing tooling, passing CI or merging the tooling PR does not resume product work. Preserve commits, dirty work, synthetic-data rules and existing review/merge gates. Never force-push or merge product changes as part of tooling propagation.

## CodeGraph 1.6.2

Use the standalone CLI installed by the workstation owner, outside application dependency graphs. `.codegraph-version` pins the reviewed version. Run `sh scripts/codegraph.sh` from any directory; the wrapper checks the exact version and disables telemetry for that invocation. Do not install or upgrade global tools during repository work. Global harness configuration belongs to the central coordinator.

From a fresh checkout:

```sh
sh scripts/codegraph.sh init --yes
sh scripts/codegraph.sh sync
sh scripts/codegraph.sh status --json
sh scripts/codegraph.sh query 'VaultBackedRepository' --limit 5 --json
sh scripts/codegraph.sh explore --max-files 3 'VaultBackedRepository'
```

For ownership or dependency questions, start with a narrow graph query and 1–3 files. Confirm relationships against source before editing. Use bounded `rg` for exact text, unsupported languages or absent graph results. Verify actual language coverage rather than assuming Kotlin or Swift support. Graph results do not prove runtime behavior or dynamic call coverage.

The CLI requires explicit `sync` after edits/checkouts; MCP watches only while active. Use `index` after changing include/exclude/extension rules. Inspect status and pending changes; release 1.6.2 has no `--strict` flag. Keep each worktree's index local, and never copy databases between worktrees. Only `.codegraph/.gitignore` is tracked under `.codegraph/`; databases, sockets, logs and daemon state must remain ignored.

Where centrally configured, the native MCP entry point is `codegraph_explore`. Otherwise use the same bounded CLI commands, including with Grok or MiniMax. No project-specific token or cost savings have been measured.

## RTK, Caveman and Ponytail

Codex follows `~/.codex/RTK.md`. If an RTK hook already processes command output, execute commands normally and do not compress it twice. Use `rtk bypass` only when compressed output is unusable. Without a hook, use supported explicit RTK CLI commands. Do not alter global hooks or settings here.

Apply installed Caveman guidance for concise, readable progress without omitting failures, safety boundaries or evidence. Apply Ponytail guidance to reuse existing code and make the smallest sufficient change. Harnesses without native skill support use these principles manually; do not duplicate whole skills into instruction files or invent slash-command support.

## TokenEconomizer and model routing

TokenEconomizer supplies repository learning, tool pruning and loop guidance only where its harness integration supports them. It is not a generic output compressor. Installation and harness settings are centrally owned; unsupported harnesses use manual guidance. Never remove required review, orchestration or device tools, and never let learned suggestions override user policy.

Antigravity is Google models only. Argon remains parked while quota-exhausted. Reserve capacity with the central coordinator before launching an Antigravity worker: initially one worker per account globally, with quota shared across its model group. Skip exhausted, disabled or unavailable routes; do not reset budgets or blindly retry.

Use exposed exact provider/model IDs. MiniMax M3.1 Flash (`m:minimax:MiniMax-M3.1-Flash:v:`) is suitable for bounded mechanical tasks when available; reserve thinking/frontier models for judgment and consequential independent review. Unknown quota remains unknown. Delegate only useful, owned tooling tasks during HOLD, with no subdelegation unless explicitly needed.
