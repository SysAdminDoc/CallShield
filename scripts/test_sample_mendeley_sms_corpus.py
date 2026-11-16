#!/usr/bin/env python3
"""Regression tests for the Mendeley SMS set's label folding, dedup and number masking."""

from sample_mendeley_sms_corpus import distinct, escape, mask_digits


def source(*rows: tuple[str, str]) -> list[dict[str, str]]:
    return [{"row": str(index), "LABEL": label, "TEXT": text} for index, (label, text) in enumerate(rows, start=1)]


def exits(rows: list[dict[str, str]]) -> bool:
    try:
        distinct(rows)
    except SystemExit:
        return True
    return False


def main() -> None:
    # Seven or more digits become a 555 number of the same length. Shorter runs stay.
    assert mask_digits("call 98321561 now") == "call 55501001 now"
    assert mask_digits("MY NO. IN LUTON 0125698789 RING ME") == "MY NO. IN LUTON 5550100199 RING ME"
    assert mask_digits("ref 456367, bus 382") == "ref 456367, bus 382"
    assert mask_digits("123456789012") == "555010019955"

    rows = distinct(
        source(
            ("Smishing", "\tWIN a prize, call 09066362231"),
            ("spam", "Ringtone club, txt TONE to 87121"),
            ("smishing", "Ringtone club, txt TONE to 87121"),
            ("ham", "I'll be there in &lt;#&gt; mins"),
            ("ham", "Is da num 98321561 familiar to u?"),
            ("Spam", "WIN a prize, call 09066362231"),
            ("ham", "   "),
        )
    )
    # One row per text, in source order, keeping the first row number.
    assert [row["row"] for row in rows] == ["1", "2", "4", "5"], rows
    # Case folds, and smishing wins whichever order the two labels come in.
    assert [row["label"] for row in rows] == ["smishing", "smishing", "ham", "ham"], rows
    # Entities decode and surrounding whitespace goes.
    assert rows[0]["text"] == "WIN a prize, call 09066362231"
    assert rows[2]["text"] == "I'll be there in <#> mins"
    # Clean texts lose their numbers, scam texts keep theirs.
    assert rows[3]["text"] == "Is da num 55501001 familiar to u?"

    # A text filed as both clean and a scam has no right answer, and neither does an unknown label.
    assert exits(source(("ham", "See you at 5"), ("spam", "See you at 5")))
    assert exits(source(("smishing", "See you at 5"), ("ham", "See you at 5")))
    assert exits(source(("phishing", "Your parcel is waiting")))

    assert escape("a\tb\nc\\d\re") == "a\\tb\\nc\\\\d\\re"
    print("Mendeley SMS sampler tests passed")


if __name__ == "__main__":
    main()
