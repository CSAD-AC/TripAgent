import json
from collections import Counter
from pathlib import Path


MODES = {"react", "graph", "multi"}
TIERS = {"smoke", "full", "holdout", "regression"}
REQUIRED = ("id", "mode", "input", "expect")
REQUIRED_EXPECT = ("requiresTool", "answerNotContains")


class DatasetError(ValueError):
    pass


def load_cases(path):
    path = Path(path)
    if not path.is_file():
        raise DatasetError(f"{path}: file not found")

    cases = []
    seen = set()
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                case = json.loads(line)
            except json.JSONDecodeError as exc:
                raise DatasetError(f"{path}:{line_no}: invalid json: {exc}") from exc
            validate_case(case, path, line_no)
            if case["id"] in seen:
                raise DatasetError(f"{path}:{line_no}: duplicate id {case['id']}")
            seen.add(case["id"])
            cases.append(case)

    if not cases:
        raise DatasetError(f"{path}: no cases")
    return cases


def validate_case(case, path, line_no):
    if not isinstance(case, dict):
        raise DatasetError(f"{path}:{line_no}: case must be an object")
    for key in REQUIRED:
        if key not in case:
            raise DatasetError(f"{path}:{line_no}: missing field {key}")

    case.setdefault("tier", "full")
    if case["mode"] not in MODES:
        raise DatasetError(f"{path}:{line_no}: unknown mode {case['mode']}")
    if case["tier"] not in TIERS:
        raise DatasetError(f"{path}:{line_no}: unknown tier {case['tier']}")
    if not isinstance(case["id"], str) or not case["id"].strip():
        raise DatasetError(f"{path}:{line_no}: id must be non-empty")

    input_data = case["input"]
    if not isinstance(input_data, dict):
        raise DatasetError(f"{path}:{line_no}: input must be an object")
    message = input_data.get("message")
    if not isinstance(message, str) or not message.strip():
        raise DatasetError(f"{path}:{line_no}: input.message must be non-empty")
    if len(message) > 4096:
        raise DatasetError(f"{path}:{line_no}: input.message exceeds 4096 chars")
    history = input_data.get("history", [])
    if not isinstance(history, list):
        raise DatasetError(f"{path}:{line_no}: input.history must be a list")
    for index, turn in enumerate(history):
        if not isinstance(turn, dict) or not isinstance(turn.get("message"), str):
            raise DatasetError(f"{path}:{line_no}: input.history[{index}].message invalid")

    expect = case["expect"]
    if not isinstance(expect, dict):
        raise DatasetError(f"{path}:{line_no}: expect must be an object")
    for key in REQUIRED_EXPECT:
        if key not in expect:
            raise DatasetError(f"{path}:{line_no}: missing expect.{key}")
    if not isinstance(expect["requiresTool"], bool):
        raise DatasetError(f"{path}:{line_no}: expect.requiresTool must be boolean")
    if not isinstance(expect["answerNotContains"], list):
        raise DatasetError(f"{path}:{line_no}: expect.answerNotContains must be a list")

    for group in expect.get("mustCallTools", []):
        if isinstance(group, str):
            continue
        if not isinstance(group, list) or not group:
            raise DatasetError(
                f"{path}:{line_no}: expect.mustCallTools entries must be non-empty strings or lists"
            )

    clarify = case.get("clarify")
    if clarify is not None:
        if not isinstance(clarify, dict) or not isinstance(clarify.get("expectAsked"), bool):
            raise DatasetError(f"{path}:{line_no}: clarify.expectAsked must be boolean")


def validate_unique_ids(cases):
    counts = Counter(case["id"] for case in cases)
    duplicates = sorted(case_id for case_id, count in counts.items() if count > 1)
    if duplicates:
        raise DatasetError(f"duplicate ids across sets: {', '.join(duplicates)}")


def resolve_set(root, name):
    if "/" not in name:
        raise DatasetError(f"set must use mode/tier form: {name}")
    mode, tier = name.split("/", 1)
    if mode == "regression" and tier == "cases":
        path = Path(root) / "evalset" / "regression" / "cases.jsonl"
    else:
        path = Path(root) / "evalset" / mode / f"{tier}.jsonl"
    if not path.is_file():
        raise DatasetError(f"set not found: {name} ({path})")
    return path


def load_sets(root, names):
    cases = []
    for name in names:
        cases.extend(load_cases(resolve_set(root, name)))
    validate_unique_ids(cases)
    return cases
