import unittest

from runner.__main__ import select_cases


class CliTest(unittest.TestCase):
    def test_case_filter_runs_before_limit(self):
        cases = [{"id": "first"}, {"id": "second"}, {"id": "target"}]

        selected = select_cases(cases, ["target"], 1)

        self.assertEqual([{"id": "target"}], selected)

    def test_limit_must_be_positive(self):
        with self.assertRaisesRegex(ValueError, "limit"):
            select_cases([{"id": "first"}], limit=0)
