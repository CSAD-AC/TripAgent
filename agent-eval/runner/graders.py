import json
import re


IGNORED_BY_DEFAULT = {"askUser", "calculator"}


def grade_case(case, trace):
    expect = case.get("expect") or {}
    failures = []
    warnings = []
    names = [call.get("name") for call in trace.get("toolCalls", []) if call.get("name")]
    ignored = set(expect.get("ignoreTools", []))
    considered = [name for name in names if name not in ignored]

    if trace.get("exception"):
        failures.append(f"run.exception:{trace['exception']}")
    if trace.get("error"):
        failures.append(f"run.error:{trace['error']}")

    for group in expect.get("mustCallTools", []):
        aliases = [group] if isinstance(group, str) else group
        if not any(name in aliases for name in considered):
            failures.append(f"tool.must_call:{aliases}")

    must_not = set(expect.get("mustNotCallTools", []))
    observed_forbidden = sorted(must_not.intersection(considered))
    if observed_forbidden:
        failures.append(f"tool.must_not_call:{observed_forbidden}")

    if expect.get("forbidAllMcpTools"):
        allowed = IGNORED_BY_DEFAULT.union(ignored)
        observed_mcp = sorted({name for name in considered if name not in allowed})
        if observed_mcp:
            failures.append(f"tool.forbid_all_mcp:{observed_mcp}")

    if expect.get("requiresTool") and not considered:
        failures.append("tool.hallucination")

    for rule in expect.get("toolArgs", []):
        tool_names = rule.get("toolAnyOf")
        if tool_names is None:
            tool = rule.get("tool")
            tool_names = [tool] if isinstance(tool, str) else list(tool or [])
        param = rule["param"]
        expected_values = {
            normalize_value(value, rule.get("normalize"))
            for value in rule.get("anyOf", [])
        }
        calls = [call for call in trace.get("toolCalls", []) if call.get("name") in tool_names]
        actual_values = set()
        for call in calls:
            try:
                arguments = json.loads(call.get("arguments") or "{}")
            except json.JSONDecodeError:
                arguments = {}
            actual_values.add(normalize_value(arguments.get(param), rule.get("normalize")))
        if not calls or not actual_values.intersection(expected_values):
            failures.append(f"tool.arg_contains:{','.join(tool_names)}.{param}")

    answer = trace.get("finalAnswer") or ""
    if not answer and not trace.get("clarified") and not trace.get("exception"):
        failures.append("answer.missing")

    for pattern in expect.get("answerRegexAll", []):
        if not re.search(pattern, answer):
            failures.append(f"answer.regex_all:{pattern}")
    any_patterns = expect.get("answerRegexAny", [])
    if any_patterns and not any(re.search(pattern, answer) for pattern in any_patterns):
        failures.append("answer.regex_any")
    for word in expect.get("answerNotContains", []):
        if word in answer:
            failures.append(f"answer.not_contains:{word}")
    for word in expect.get("answerNotContainsIfNoTool", []):
        if not considered and word in answer:
            failures.append(f"answer.not_contains_if_no_tool:{word}")
    if expect.get("answerMinLength") and len(answer) < expect["answerMinLength"]:
        failures.append("answer.min_length")
    if expect.get("answerMaxLength") and len(answer) > expect["answerMaxLength"]:
        failures.append("answer.max_length")
    if expect.get("answerIncludesDate") and not re.search(
        r"(\d{4}-\d{2}-\d{2}|今天|明天|后天|\d{1,2}月\d{1,2}日)", answer
    ):
        failures.append("answer.includes_date")

    node_data = trace.get("nodeData") or {}
    constraints_rule = expect.get("constraints") or {}
    constraints = node_data.get("constraints") or {}
    for field, allowed in (constraints_rule.get("required") or {}).items():
        actual = normalize_value(constraints.get(field))
        expected = {normalize_value(value) for value in allowed}
        if actual not in expected:
            failures.append(f"constraints.recall:{field}")
    for field in constraints_rule.get("forbiddenPresent", []):
        value = constraints.get(field)
        if value not in (None, "", [], {}):
            failures.append(f"constraints.no_fabrication:{field}")
    soft_patterns = constraints_rule.get("softRequirementsAny") or []
    soft_text = " ".join(str(item) for item in constraints.get("softRequirements") or [])
    for pattern in soft_patterns:
        if not re.search(pattern, soft_text):
            failures.append(f"constraints.soft_requirement:{pattern}")

    budget = node_data.get("budget") or {}
    if expect.get("budgetConsistent"):
        if not budget:
            failures.append("budget.missing")
        else:
            breakdown = (budget.get("breakdown") or {}).values()
            total = budget.get("totalCost")
            limit = budget.get("budget")
            overage = budget.get("overageAmount")
            if not isinstance(total, (int, float)) or sum(breakdown) != total:
                failures.append("budget.breakdown_sum")
            if limit is not None:
                expected_over = total > limit
                if bool(budget.get("isOverBudget")) != expected_over:
                    failures.append("budget.over_budget_flag")
                expected_overage = max(0, total - limit)
                if overage != expected_overage:
                    failures.append("budget.overage_amount")

    if "budgetWithinUserBudget" in expect:
        if not budget:
            failures.append("budget.missing")
        elif budget.get("totalCost", 0) > expect["budgetWithinUserBudget"]:
            failures.append("budget.respect")

    if "itineraryDaysMatch" in expect:
        days = (node_data.get("dayplans") or {}).get("days") or []
        if len(days) != expect["itineraryDaysMatch"]:
            failures.append("itinerary.shape")

    if expect.get("terminalNode") and expect["terminalNode"] not in node_data:
        failures.append(f"process.terminal_node:{expect['terminalNode']}")

    if expect.get("maxIterations") and trace.get("iterationCount", 0) > expect["maxIterations"]:
        failures.append("process.max_iterations")
    if expect.get("latencyBudgetMs") and trace.get("durationMs", 0) > expect["latencyBudgetMs"]:
        warnings.append("process.latency")
    if case["mode"] == "graph" and expect.get("noNodeError", True) and trace.get("nodeErrors", 0):
        failures.append("process.no_node_error")
    if "maxFallbacks" in expect and trace.get("graphIterations", 0) > expect["maxFallbacks"]:
        warnings.append("process.max_fallbacks")

    clarify = case.get("clarify") or {}
    expect_asked = bool(clarify.get("expectAsked", False))
    if trace.get("clarified") != expect_asked:
        failures.append("clarify.missed" if expect_asked else "clarify.over")

    verdict = "FAIL" if failures else ("DEGRADED" if warnings else "PASS")
    return {
        "caseId": case["id"],
        "mode": case["mode"],
        "tier": case.get("tier", "full"),
        "verdict": verdict,
        "failures": failures,
        "warnings": warnings,
    }


def normalize_value(value, kind=None):
    if value is None:
        return ""
    text = re.sub(r"\s+", "", str(value)).casefold()
    if kind == "city":
        return text.removesuffix("市")
    if kind == "station":
        return text.upper()
    return text
