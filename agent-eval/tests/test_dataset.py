import json
import tempfile
import unittest
from pathlib import Path

from runner.dataset import DatasetError, load_cases, validate_unique_ids


class DatasetTest(unittest.TestCase):
    def write_jsonl(self, rows):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "cases.jsonl"
        path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
            encoding="utf-8",
        )
        return path

    def test_load_cases_accepts_minimum_schema(self):
        path = self.write_jsonl([
            {
                "id": "react-weather-001",
                "mode": "react",
                "tier": "smoke",
                "input": {"message": "北京今天天气"},
                "expect": {"requiresTool": True, "answerNotContains": ["无法确定"]},
            }
        ])

        cases = load_cases(path)

        self.assertEqual(1, len(cases))
        self.assertEqual("react-weather-001", cases[0]["id"])

    def test_load_cases_rejects_duplicate_id(self):
        row = {
            "id": "react-weather-001",
            "mode": "react",
            "input": {"message": "北京今天天气"},
            "expect": {"requiresTool": True, "answerNotContains": ["无法确定"]},
        }
        path = self.write_jsonl([row, row])

        with self.assertRaisesRegex(DatasetError, "duplicate id"):
            load_cases(path)

    def test_load_cases_rejects_unknown_mode(self):
        path = self.write_jsonl([
            {
                "id": "unknown-001",
                "mode": "workflow",
                "input": {"message": "test"},
                "expect": {"requiresTool": False, "answerNotContains": []},
            }
        ])

        with self.assertRaisesRegex(DatasetError, "unknown mode"):
            load_cases(path)

    def test_validate_unique_ids_across_sets(self):
        with self.assertRaisesRegex(DatasetError, "duplicate ids across sets"):
            validate_unique_ids([{"id": "same"}, {"id": "same"}])
