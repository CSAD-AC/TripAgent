import io
import unittest
from unittest.mock import patch

from runner.sse_client import consume_event, iter_sse, new_trace, run_case


class SseClientTest(unittest.TestCase):
    def test_iter_sse_reads_multiline_data(self):
        stream = io.StringIO(
            "event: tool_call\n"
            'data: {"type":"tool_call","toolName":"amapWeather"}\n'
            "\n"
            "event: final\n"
            'data: {"type":"final","content":"晴"}\n'
            "\n"
        )

        events = list(iter_sse(stream))

        self.assertEqual(2, len(events))
        self.assertEqual("tool_call", events[0]["type"])
        self.assertEqual("amapWeather", events[0]["toolName"])
        self.assertEqual("final", events[1]["type"])

    def test_consume_event_collects_trace(self):
        trace = new_trace("case-1")
        consume_event(trace, {
            "type": "session_init",
            "conversationId": "conversation-1",
            "traceId": "trace-1",
        })
        consume_event(trace, {"type": "tool_call", "toolName": "amapWeather", "toolArguments": "{}"})
        consume_event(trace, {"type": "iteration_separator"})
        consume_event(trace, {"type": "node_data", "dataType": "budget", "data": {"totalCost": 10}})
        stopped = consume_event(trace, {"type": "final", "content": "done"})

        self.assertTrue(stopped)
        self.assertEqual("done", trace["finalAnswer"])
        self.assertEqual(1, trace["iterationCount"])
        self.assertEqual("amapWeather", trace["toolCalls"][0]["name"])
        self.assertEqual(10, trace["nodeData"]["budget"]["totalCost"])

    def test_consume_event_ignores_other_conversations(self):
        trace = new_trace("case-1")
        consume_event(trace, {
            "type": "session_init",
            "conversationId": "conversation-1",
            "traceId": "trace-1",
        })

        consume_event(trace, {
            "type": "node_data",
            "conversationId": "conversation-2",
            "dataType": "constraints",
            "data": {"days": 3},
        })

        self.assertNotIn("constraints", trace["nodeData"])
        self.assertEqual(1, trace["foreignEventCount"])

    @patch("runner.sse_client._stream_once")
    def test_run_case_continues_after_history_clarification(self, stream_once):
        first_trace = new_trace("graph-missing-001")
        first_trace["conversationId"] = "conversation-1"
        first_trace["clarified"] = True
        final_trace = new_trace("graph-missing-001", "conversation-1")
        final_trace["finalAnswer"] = "已按补充信息完成规划"
        stream_once.side_effect = [first_trace, final_trace]
        case = {
            "id": "graph-missing-001",
            "mode": "graph",
            "input": {
                "message": "预算 8000，2 个大人带 1 个小孩",
                "history": [{"message": "我们想去西安玩几天"}],
            },
        }

        trace = run_case("http://localhost:8080", case)

        self.assertFalse(trace["clarified"])
        self.assertEqual("已按补充信息完成规划", trace["finalAnswer"])
        self.assertEqual("conversation-1", stream_once.call_args_list[1].args[3])
        self.assertEqual(1, len(trace["historyTraces"]))
