# TravelPal Agent Eval

## Validate

```powershell
cd TripAgent/agent-eval
python -m runner validate
```

Validate the regression set:

```powershell
python -m runner validate --set regression/cases
```

## Run smoke

Start `mcp-server` and `agent-core`, then run:

```powershell
cd TripAgent/agent-eval
python -m runner run --set react/smoke --set graph/smoke --set multi/smoke
```

Run a small connectivity check first:

```powershell
python -m runner run --set react/smoke --limit 1
```

For long Graph runs, the SSE overall deadline and socket idle timeout are separate:

```powershell
python -m runner run --set graph/smoke --timeout 300 --connect-timeout 300
```

Reports are written to `runs/时间戳/`:

- `manifest.json`: run configuration
- `runs.jsonl`: complete SSE event traces
- `scores.json`: per-case grader output
- `summary.json`: aggregate pass@1 and pass^k
- `report.md`: readable failure report

## Rules

- `mustCallTools` is a two-dimensional array. Every outer item must match, and any tool in the inner alias list is accepted.
- Fast-changing facts are checked by shape, tool trajectory and temporal anchors. Do not freeze current weather, ticket availability or prices.
- On `clarification_request`, the runner disconnects immediately and never calls `/api/chat/answer`.
- Every real bug gets a regression case after it is fixed. Regression cases are append-only.
