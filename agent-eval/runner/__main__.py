import argparse
import json
from datetime import datetime
from pathlib import Path

from runner.dataset import DatasetError, load_sets
from runner.graders import grade_case
from runner.report import summarize, write_report
from runner.sse_client import run_case


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SETS = ["react/smoke", "graph/smoke", "multi/smoke"]


def build_parser():
    parser = argparse.ArgumentParser(description="TravelPal agent evaluation runner")
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser("validate", help="validate JSONL datasets")
    validate.add_argument("--set", action="append", default=[])

    run = sub.add_parser("run", help="run cases against agent-core")
    run.add_argument("--set", action="append", default=[])
    run.add_argument("--base-url", default="http://localhost:8080")
    run.add_argument("--out")
    run.add_argument("--limit", type=int)
    run.add_argument("--case", action="append", default=[])
    run.add_argument("--times", type=int, default=1)
    run.add_argument("--timeout", type=int, default=180)
    run.add_argument("--connect-timeout", type=int, default=None)
    return parser


def select_cases(cases, case_ids=None, limit=None):
    selected = list(cases)
    requested = set(case_ids or [])
    if requested:
        selected = [case for case in selected if case["id"] in requested]
        missing = requested.difference(case["id"] for case in selected)
        if missing:
            raise ValueError(f"case not found: {', '.join(sorted(missing))}")
    if limit is not None:
        if limit < 1:
            raise ValueError("--limit must be >= 1")
        selected = selected[:limit]
    return selected


def main(argv=None):
    args = build_parser().parse_args(argv)
    set_names = args.set or DEFAULT_SETS
    if args.command == "run":
        if args.times < 1:
            raise SystemExit("--times must be >= 1")

    try:
        cases = load_sets(ROOT, set_names)
    except DatasetError as exc:
        raise SystemExit(str(exc)) from exc

    if args.command == "validate":
        modes = {}
        for case in cases:
            modes[case["mode"]] = modes.get(case["mode"], 0) + 1
        print(f"validated {len(cases)} cases: {json.dumps(modes, ensure_ascii=False)}")
        return

    try:
        cases = select_cases(cases, args.case, args.limit)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc
    if not cases:
        raise SystemExit("no cases selected")

    started_at = datetime.now().astimezone()
    results = []
    traces = []
    total_runs = len(cases) * args.times
    run_number = 0
    for case in cases:
        for run_index in range(1, args.times + 1):
            run_number += 1
            print(f"[{run_number}/{total_runs}] {case['id']} run={run_index}")
            trace = run_case(
                args.base_url,
                case,
                timeout_sec=args.timeout,
                connect_timeout_sec=args.connect_timeout,
            )
            result = grade_case(case, trace)
            result["runIndex"] = run_index
            result["traceId"] = trace.get("traceId")
            result["durationMs"] = trace.get("durationMs", 0)
            result["answerPreview"] = (trace.get("finalAnswer") or "")[:300]
            result["toolNames"] = [
                call.get("name") for call in trace.get("toolCalls", []) if call.get("name")
            ]
            results.append(result)
            traces.append(trace)

    summary = summarize(results)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = Path(args.out) if args.out else ROOT / "runs" / stamp
    metadata = {
        "startedAt": started_at.isoformat(),
        "baseUrl": args.base_url,
        "sets": set_names,
        "times": args.times,
        "totalRuns": len(results),
    }
    write_report(out_dir, summary, results, traces=traces, metadata=metadata)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"report: {out_dir.resolve()}")
    raise SystemExit(0 if summary["failed"] == 0 else 1)


if __name__ == "__main__":
    main()
