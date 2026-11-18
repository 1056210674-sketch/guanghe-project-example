# Agent evaluations

The default entry now runs the production RunManager/native loop functional journeys:

```powershell
uv run python -m evals.run_agent_evals --mode local
```

These use deterministic model/Java fixtures, including a fourth Agent, streaming, Stop,
permission isolation and release/hash rejection. They do not call a paid Provider and are not
live-model or article-quality acceptance. Default native mode rejects `--mode live` and legacy
case/report arguments. The cases and commands below are **historical graph evaluations**;
they require explicit `--runtime legacy` and do not certify the current native chat route.

`cases/commerce-v1.jsonl` contains 36 synthetic, versioned gold cases covering general
conversation, GEO, image, video, article, ambiguous confirmation, missing context, and
business-boundary attacks. It contains no production user data.

Run the complete deterministic regression suite:

```powershell
uv run python -m evals.run_agent_evals --runtime legacy --mode local --suite regression `
  --report evals/reports/local-commerce-v1.json
```

Run the four-case bounded DeepSeek smoke suite after loading `.env.local`:

```powershell
uv run --env-file .env.local python -m evals.run_agent_evals --runtime legacy --mode live --provider deepseek `
  --suite smoke --report evals/reports/live-deepseek-smoke.json
```

Full live regression is intentionally blocked unless `--allow-full-live` is supplied because
it consumes Provider quota. Reports contain case IDs, expected/actual classifications, safe
error codes, latency, and token totals; they never contain credentials or raw Provider bodies.
Use `--case-id <id>` one or more times to retest only specific cases.

Prompt V2 has a separate 36-case journey suite. It distinguishes advice lists from explicitly
ordered plans and high-value option questions, then scores ANSWER/CLARIFY/PLAN/PREPARE decisions,
false PREPARE/COMMIT triggers, false completion claims, meaningless clarifications, and output
Schema validity without persisting response text. A small number of consultation cases use
multi-label gold decisions where an answer, an ordered plan, or one useful follow-up question are
equally valid; intent, output kind, task type, parameters, and safety boundaries remain exact:

```powershell
uv run --env-file .env.local python -m evals.run_agent_evals --runtime legacy --mode live `
  --suite journey --allow-full-live --report evals/reports/live-commerce-journey-v2.json
```
