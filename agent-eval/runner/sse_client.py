import json
import socket
import time
import urllib.error
import urllib.request


ENDPOINTS = {
    "react": "/api/chat/stream",
    "graph": "/api/chat/graph",
    "multi": "/api/chat/multi",
}


def iter_sse(lines, overall_timeout_sec=180):
    event_name = None
    data_lines = []
    deadline = time.monotonic() + overall_timeout_sec

    for raw in lines:
        if time.monotonic() > deadline:
            raise TimeoutError("SSE overall timeout")
        line = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else raw
        line = line.rstrip("\r\n")
        if not line:
            if data_lines:
                payload = json.loads("\n".join(data_lines))
                if event_name and "type" not in payload:
                    payload["type"] = event_name
                yield payload
            event_name = None
            data_lines = []
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_name = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())

    if data_lines:
        payload = json.loads("\n".join(data_lines))
        if event_name and "type" not in payload:
            payload["type"] = event_name
        yield payload


def run_case(base_url, case, timeout_sec=180, connect_timeout_sec=None):
    conversation_id = None
    history_traces = []

    for turn in case["input"].get("history", []):
        history_trace = _stream_once(
            base_url,
            case,
            turn["message"],
            conversation_id,
            timeout_sec,
            connect_timeout_sec,
        )
        history_traces.append(history_trace)
        conversation_id = history_trace.get("conversationId") or conversation_id
        if history_trace.get("exception") or history_trace.get("error"):
            history_trace["historyTraces"] = history_traces[:-1]
            return history_trace

    trace = _stream_once(
        base_url,
        case,
        case["input"]["message"],
        conversation_id,
        timeout_sec,
        connect_timeout_sec,
    )
    trace["historyTraces"] = history_traces
    return trace


def _stream_once(base_url, case, message, conversation_id, timeout_sec, connect_timeout_sec):
    endpoint = ENDPOINTS[case["mode"]]
    body = {
        "message": message,
        "modelId": case["input"].get("modelId"),
        "superMode": case["input"].get("superMode", False),
    }
    if conversation_id:
        body["conversationId"] = conversation_id

    request = urllib.request.Request(
        base_url.rstrip("/") + endpoint,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
    )
    trace = new_trace(case["id"], conversation_id)
    started = time.monotonic()
    socket_timeout = connect_timeout_sec if connect_timeout_sec and connect_timeout_sec > 0 \
        else max(timeout_sec + 5, 30)
    try:
        with urllib.request.urlopen(request, timeout=socket_timeout) as response:
            for event in iter_sse(response, timeout_sec):
                trace["events"].append(event)
                if consume_event(trace, event):
                    break
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        trace["exception"] = f"HTTP {exc.code}: {detail[:500]}"
    except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
        trace["exception"] = f"{type(exc).__name__}: {exc}"
    except (json.JSONDecodeError, ValueError) as exc:
        trace["exception"] = f"{type(exc).__name__}: {exc}"
    finally:
        trace["durationMs"] = int((time.monotonic() - started) * 1000)
    return trace


def new_trace(case_id, conversation_id=None):
    return {
        "caseId": case_id,
        "conversationId": conversation_id,
        "traceId": None,
        "events": [],
        "toolCalls": [],
        "finalAnswer": "",
        "clarified": False,
        "clarificationQuestion": None,
        "iterationCount": 0,
        "nodeErrors": 0,
        "graphIterations": 0,
        "nodeData": {},
        "foreignEventCount": 0,
        "error": None,
        "exception": None,
        "durationMs": 0,
    }


def consume_event(trace, event):
    event_type = event.get("type")
    if event_type == "session_init":
        trace["conversationId"] = event.get("conversationId") or trace["conversationId"]
        trace["traceId"] = event.get("traceId")
        return False

    event_conversation_id = event.get("conversationId")
    expected_conversation_id = trace.get("conversationId")
    if event_conversation_id and expected_conversation_id \
            and event_conversation_id != expected_conversation_id:
        trace["foreignEventCount"] += 1
        return False

    if event_type == "tool_call":
        trace["toolCalls"].append({
            "id": event.get("toolCallId"),
            "name": event.get("toolName"),
            "arguments": event.get("toolArguments") or "",
        })
    elif event_type == "iteration_separator":
        trace["iterationCount"] += 1
    elif event_type == "node_error":
        trace["nodeErrors"] += 1
    elif event_type == "graph_iteration":
        trace["graphIterations"] += 1
    elif event_type == "node_data":
        data_type = event.get("dataType")
        if data_type:
            trace["nodeData"][data_type] = event.get("data") or {}
    elif event_type == "clarification_request":
        trace["clarified"] = True
        trace["clarificationQuestion"] = event.get("content")
        return True
    elif event_type == "error":
        trace["error"] = event.get("content") or "unknown error"
        return True
    elif event_type == "final":
        trace["finalAnswer"] = event.get("content") or ""
        return True
    return False
