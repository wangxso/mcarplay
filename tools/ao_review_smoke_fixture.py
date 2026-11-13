"""Disposable fixture for a manual AO review-loop rehearsal; never ship."""


def blocking_findings(findings):
    return [finding for finding in findings if finding["severity"] in {"P0", "P1", "P2"}]
