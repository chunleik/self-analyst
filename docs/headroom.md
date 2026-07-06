# Headroom

SelfAnalyst can optionally use [Headroom](https://github.com/headroomlabs-ai/headroom)
as a local OpenAI-compatible proxy for LLM context compression. This integration
is disabled by default and is not required to run the app.

## Runtime Token Reduction

Start Headroom yourself:

```powershell
headroom proxy --port 8787
```

Then set:

```toml
[headroom]
enabled = true
proxy-url = "http://127.0.0.1:8787/v1"
stats.enabled = true
output-shaper = false
```

Restart the SelfAnalyst backend after changing these values. When the proxy is
reachable, Agent chat and plain summary completions use the Headroom base URL.
When the proxy is not reachable, SelfAnalyst falls back to `llm.base-url`.

Embedding and speech-to-text audio upload requests do not go through Headroom in
the first version.

## Development Memory And Failure Learning

Headroom memory/failure learning is for repository development context only. It
can store stable project facts such as module paths, build commands, CodeGraph
usage expectations, and repeatable fixes learned from failed agent sessions.

Preview learning suggestions:

```powershell
headroom learn
```

Apply suggestions only after review:

```powershell
headroom learn --apply
```

Durable team-wide rules may be copied into `AGENTS.md` or `CLAUDE.md` only when
they are stable, non-sensitive, and useful to all agents. Keep machine-specific
or personal preferences in a local ignored file.

Never store these in Headroom development memory:

- SelfAnalyst user activity data
- OCR/UIA screen text
- audio transcripts
- API keys, tokens, or passwords
- private file contents
