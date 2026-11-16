import json
from collections import Counter, defaultdict
from pathlib import Path


def sanitize_for_json(value):
    if isinstance(value, str):
        try:
            value.encode("utf-8")
            return value
        except UnicodeEncodeError:
            return value.encode("utf-16", "surrogatepass").decode("utf-16", "replace")
    if isinstance(value, list):
        return [sanitize_for_json(item) for item in value]
    if isinstance(value, dict):
        return {
            sanitize_for_json(key): sanitize_for_json(item)
            for key, item in value.items()
        }
    return value


def write_json(path, value):
    Path(path).write_text(
        json.dumps(sanitize_for_json(value), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def summarize(results):
    total = len(results)
    passed = sum(result["verdict"] == "PASS" for result in results)
    failed = sum(result["verdict"] == "FAIL" for result in results)
    degraded = sum(result["verdict"] == "DEGRADED" for result in results)
    pass_rate = round(passed * 100.0 / total, 2) if total else 0.0

    by_mode = {}
    for mode in ("react", "graph", "multi"):
        mode_results = [result for result in results if result.get("mode") == mode]
        mode_passed = sum(result["verdict"] == "PASS" for result in mode_results)
        by_mode[mode] = {
            "total": len(mode_results),
            "passed": mode_passed,
            "passRate": round(mode_passed * 100.0 / len(mode_results), 2)
            if mode_results else 0.0,
        }

    grouped = defaultdict(list)
    for result in results:
        grouped[result["caseId"]].append(result["verdict"])
    stable_cases = sum(all(verdict == "PASS" for verdict in verdicts) for verdicts in grouped.values())
    pass_at_k = round(stable_cases * 100.0 / len(grouped), 2) if grouped else 0.0

    failure_counts = Counter()
    for result in results:
        for failure in result.get("failures", []):
            failure_counts[failure.split(":", 1)[0]] += 1

    return {
        "total": total,
        "uniqueCases": len(grouped),
        "passed": passed,
        "failed": failed,
        "degraded": degraded,
        "passRate": pass_rate,
        "passAtK": pass_at_k,
        "byMode": by_mode,
        "failureCounts": dict(sorted(failure_counts.items())),
    }


def write_report(out_dir, summary, results, traces=None, metadata=None):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if metadata:
        write_json(out_dir / "manifest.json", metadata)
    write_json(out_dir / "summary.json", summary)
    write_json(out_dir / "scores.json", results)
    if traces is not None:
        with (out_dir / "runs.jsonl").open("w", encoding="utf-8") as handle:
            for trace in traces:
                handle.write(
                    json.dumps(sanitize_for_json(trace), ensure_ascii=False) + "\n"
                )

    lines = [
        "# Eval Report",
        "",
        f"- total runs: {summary['total']}",
        f"- unique cases: {summary['uniqueCases']}",
        f"- passed: {summary['passed']}",
        f"- failed: {summary['failed']}",
        f"- degraded: {summary['degraded']}",
        f"- pass@1: {summary['passRate']}%",
        f"- pass^k: {summary['passAtK']}%",
        "",
        "## By Mode",
        "",
        "| mode | total | passed | pass@1 |",
        "|---|---:|---:|---:|",
    ]
    for mode, stats in summary["byMode"].items():
        lines.append(
            f"| {mode} | {stats['total']} | {stats['passed']} | {stats['passRate']}% |"
        )

    lines.extend(["", "## Failures", ""])
    failed_results = [result for result in results if result["verdict"] == "FAIL"]
    if not failed_results:
        lines.append("No hard failures.")
    for result in failed_results:
        lines.append(
            f"- `{result['caseId']}` run {result.get('runIndex', 1)}: "
            f"{', '.join(result['failures'])}"
        )
        if result.get("answerPreview"):
            lines.append(f"  answer: {result['answerPreview']}")
        if result.get("toolNames"):
            lines.append(f"  tools: {', '.join(result['toolNames'])}")

    warnings = [result for result in results if result["verdict"] == "DEGRADED"]
    lines.extend(["", "## Degraded", ""])
    if not warnings:
        lines.append("No degraded cases.")
    for result in warnings:
        lines.append(
            f"- `{result['caseId']}` run {result.get('runIndex', 1)}: "
            f"{', '.join(result['warnings'])}"
        )

    (out_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
