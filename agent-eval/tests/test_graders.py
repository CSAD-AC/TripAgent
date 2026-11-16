import unittest

from runner.graders import grade_case


def trace_for(case_id):
    return {
        "caseId": case_id,
        "events": [],
        "toolCalls": [],
        "finalAnswer": "",
        "clarified": False,
        "iterationCount": 1,
        "nodeErrors": 0,
        "graphIterations": 0,
        "nodeData": {},
        "durationMs": 1000,
        "error": None,
        "exception": None,
    }


class GradersTest(unittest.TestCase):
    def test_alias_group_any_match_passes(self):
        case = {
            "id": "react-weather-001",
            "mode": "react",
            "expect": {
                "requiresTool": True,
                "answerNotContains": [],
                "mustCallTools": [["amapWeather", "amapForecast"]],
            },
        }
        trace = trace_for("react-weather-001")
        trace["toolCalls"] = [{"name": "amapForecast", "arguments": "{}"}]
        trace["finalAnswer"] = "杭州明天有雨"

        result = grade_case(case, trace)

        self.assertEqual("PASS", result["verdict"])

    def test_requires_tool_without_tool_call_fails(self):
        case = {
            "id": "react-weather-002",
            "mode": "react",
            "expect": {"requiresTool": True, "answerNotContains": []},
        }
        trace = trace_for("react-weather-002")
        trace["finalAnswer"] = "北京晴 25 度"

        result = grade_case(case, trace)

        self.assertEqual("FAIL", result["verdict"])
        self.assertIn("tool.hallucination", result["failures"])

    def test_tool_args_accept_alias_group(self):
        case = {
            "id": "react-ticket-002",
            "mode": "react",
            "expect": {
                "requiresTool": True,
                "answerNotContains": [],
                "mustCallTools": [["getTickets", "getInterlineTickets"]],
                "toolArgs": [{
                    "toolAnyOf": ["getTickets", "getInterlineTickets"],
                    "param": "fromStation",
                    "anyOf": ["北京", "VNP"],
                }],
            },
        }
        trace = trace_for("react-ticket-002")
        trace["toolCalls"] = [{
            "name": "getInterlineTickets",
            "arguments": '{"fromStation":"北京","toStation":"上海"}',
        }]
        trace["finalAnswer"] = "有候补余票"

        result = grade_case(case, trace)

        self.assertEqual("PASS", result["verdict"])

    def test_graph_constraints_are_checked_from_node_data(self):
        case = {
            "id": "graph-plan-001",
            "mode": "graph",
            "expect": {
                "requiresTool": False,
                "answerNotContains": [],
                "constraints": {
                    "required": {
                        "destination": ["成都"],
                        "days": [3],
                        "companions": [2],
                        "budget": [5000],
                    }
                },
                "budgetConsistent": True,
                "itineraryDaysMatch": 3,
                "terminalNode": "report",
            },
        }
        trace = trace_for("graph-plan-001")
        trace["finalAnswer"] = "成都三日游"
        trace["nodeData"] = {
            "constraints": {
                "destination": "成都",
                "days": 3,
                "companions": 2,
                "budget": 5000,
            },
            "budget": {
                "breakdown": {"交通": 1000, "住宿": 2000},
                "totalCost": 3000,
                "budget": 5000,
                "isOverBudget": False,
                "overageAmount": 0,
            },
            "dayplans": {"days": [{"dayIndex": 1}, {"dayIndex": 2}, {"dayIndex": 3}]},
            "report": {"summary": "成都三日游"},
        }

        result = grade_case(case, trace)

        self.assertEqual("PASS", result["verdict"])

    def test_clarification_expectation(self):
        case = {
            "id": "graph-missing-001",
            "mode": "graph",
            "expect": {"requiresTool": False, "answerNotContains": []},
            "clarify": {"expectAsked": True},
        }
        trace = trace_for("graph-missing-001")
        trace["clarified"] = True

        result = grade_case(case, trace)

        self.assertEqual("PASS", result["verdict"])
