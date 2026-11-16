import json
import tempfile
import unittest
from pathlib import Path

from runner.report import summarize, write_report


class ReportTest(unittest.TestCase):
    def test_summarize_counts_verdicts(self):
        results = [
            {"caseId": "a", "mode": "react", "verdict": "PASS", "failures": []},
            {"caseId": "b", "mode": "graph", "verdict": "FAIL", "failures": ["tool.must_call"]},
            {"caseId": "c", "mode": "multi", "verdict": "DEGRADED", "failures": []},
        ]

        summary = summarize(results)

        self.assertEqual(3, summary["total"])
        self.assertEqual(1, summary["passed"])
        self.assertEqual(1, summary["failed"])
        self.assertAlmostEqual(33.33, summary["passRate"], places=2)

    def test_write_report_creates_artifacts(self):
        results = [{
            "caseId": "a",
            "mode": "react",
            "verdict": "PASS",
            "failures": [],
            "warnings": [],
        }]
        summary = summarize(results)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "run"
            write_report(out, summary, results, traces=[{"caseId": "a"}], metadata={"baseUrl": "x"})

            self.assertTrue((out / "report.md").is_file())
            self.assertTrue((out / "summary.json").is_file())
            self.assertTrue((out / "scores.json").is_file())
            self.assertTrue((out / "runs.jsonl").is_file())
            self.assertEqual("x", json.loads((out / "manifest.json").read_text())["baseUrl"])
