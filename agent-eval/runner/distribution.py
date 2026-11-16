def validate_distribution(cases):
    counts = {"react": 0, "graph": 0, "multi": 0}
    for case in cases:
        mode = case["mode"]
        if mode not in counts:
            raise AssertionError(f"unknown mode: {mode}")
        counts[mode] += 1
    expected = {"react": 80, "graph": 50, "multi": 20}
    if counts != expected:
        raise AssertionError(f"expected {expected}, got {counts}")
    return counts
