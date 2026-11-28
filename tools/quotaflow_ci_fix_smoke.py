def classify_runner_failure(code: str) -> str:
    if code == "network":
        return "infrastructure"
    return "code_or_test"
