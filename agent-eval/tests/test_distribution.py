import unittest

from runner.distribution import validate_distribution


class DistributionTest(unittest.TestCase):
    def test_exact_distribution(self):
        cases = (
            [{"mode": "react"}] * 80
            + [{"mode": "graph"}] * 50
            + [{"mode": "multi"}] * 20
        )

        counts = validate_distribution(cases)

        self.assertEqual({"react": 80, "graph": 50, "multi": 20}, counts)

    def test_wrong_distribution_fails(self):
        with self.assertRaisesRegex(AssertionError, "expected"):
            validate_distribution([{"mode": "react"}])
