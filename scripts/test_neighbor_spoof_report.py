#!/usr/bin/env python3
"""Regression tests for the neighbor-spoof calibration report's counting."""

from neighbor_spoof_report import TOLL_FREE_AREA_CODES, chance_share, complaint_row, summarize


def record(record_id: str, caller: str, consumer: str, subject: str = "Other", robocall: str = "N") -> dict:
    return {
        "id": record_id,
        "attributes": {
            "company-phone-number": caller,
            "consumer-area-code": consumer,
            "subject": subject,
            "recorded-message-or-robocall": robocall,
        },
    }


def rows(*records: dict) -> list[dict]:
    parsed = [complaint_row(item) for item in records]
    assert all(parsed), parsed
    return [row for row in parsed if row]


def main() -> None:
    # FTC sends the caller as ten bare digits and the consumer's area code on its own.
    row = complaint_row(record("a", "2129460192", "212", "Medical & prescriptions", "Y"))
    assert row == {
        "id": "a",
        "caller_area_code": "212",
        "consumer_area_code": "212",
        "subject": "Medical & prescriptions",
        "robocall": True,
    }, row
    # Either side missing or malformed leaves the complaint out.
    assert complaint_row(record("b", "2129460192", "")) is None
    assert complaint_row(record("c", "2129460192", "012")) is None
    assert complaint_row(record("d", "2129460192", "21")) is None
    assert complaint_row(record("e", "", "212")) is None
    assert complaint_row(record("f", "5551234", "212")) is None
    assert complaint_row({"id": "g", "attributes": None}) is None
    assert (complaint_row(record("h", "2129460192", "212", subject="")) or {}).get("subject") == "No Subject Provided"

    assert "800" in TOLL_FREE_AREA_CODES and "212" not in TOLL_FREE_AREA_CODES

    sample = rows(
        record("1", "2129460192", "212", "Medical", "Y"),
        record("2", "2129460193", "718", "Medical", "N"),
        record("3", "7184570192", "718", "Debt", "Y"),
        record("4", "6464570192", "212", "Debt", "N"),
        record("5", "8004570192", "212", "Debt", "Y"),
    )
    summary = summarize(sample, min_subject=1)
    # Two of the five share the consumer's area code.
    assert summary["complaints"] == 5
    assert summary["same_area_code_pct"] == 40.0
    local = summary["excluding_toll_free"]
    # The toll-free caller drops out: two of four.
    assert local["complaints"] == 4
    assert local["same_area_code_pct"] == 50.0
    # Callers 212 x2, 718, 646 against consumers 212 x2, 718 x2:
    # (2 * 2 + 2 * 1) / (4 * 4) = 37.5% by chance, and 50 / 37.5 = 1.3 times that.
    assert local["chance_pct"] == 37.5, local
    assert local["times_chance"] == 1.3, local
    assert summary["by_robocall"] == {
        "robocall": {"complaints": 2, "same_area_code_pct": 100.0},
        "live caller": {"complaints": 2, "same_area_code_pct": 0.0},
    }, summary["by_robocall"]
    assert summary["by_subject"] == [
        {"subject": "Medical", "complaints": 2, "same_area_code_pct": 50.0},
        {"subject": "Debt", "complaints": 2, "same_area_code_pct": 50.0},
    ], summary["by_subject"]
    # A subject under the floor gets no row of its own.
    assert summarize(sample, min_subject=3)["by_subject"] == []

    # Callers spread evenly over area codes the consumers never have give no chance matches.
    assert chance_share(rows(record("x", "2129460192", "718"), record("y", "3124570192", "415"))) == 0.0
    assert chance_share([]) == 0.0
    print("neighbor spoof report tests passed")


if __name__ == "__main__":
    main()
