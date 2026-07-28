#!/usr/bin/env python3
"""Regression tests for Python report/import phone normalization."""

from phone_normalization import (
    is_plausible_number,
    normalize_nanp_number,
    normalize_phone_number,
    normalize_report_number,
    validated_report_number,
)


def main() -> None:
    assert normalize_phone_number("+1 (212) 555-1234") == "+12125551234"
    assert normalize_phone_number("\u200E+\u200F1 212\u200B-555\u200E-1234") == "+12125551234"
    assert normalize_phone_number("\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660") == ""
    assert normalize_phone_number("\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19\uFF10") == ""

    assert normalize_report_number("212-555-1234") == "+12125551234"
    assert normalize_report_number("+442071234567") == "+442071234567"
    assert normalize_report_number("+1234567890123456") is None
    assert normalize_report_number("\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660") is None

    assert normalize_nanp_number("212-555-1234") == "+12125551234"
    assert normalize_nanp_number("+442071234567") is None

    # Plausibility gating at the report trust boundary.
    assert is_plausible_number("+12122345678") is True          # valid NANP
    assert is_plausible_number("+442071234567") is True          # valid UK
    assert is_plausible_number("+15551234567") is False          # NANP area code 555
    assert is_plausible_number("+12125550101") is False          # 555 exchange (fiction)
    assert is_plausible_number("+12119345678") is False          # N11 area code (211)
    assert is_plausible_number("+01145884697") is False          # leading-zero country code
    assert is_plausible_number("+1234567") is False              # too short

    assert validated_report_number("212-234-5678") == "+12122345678"
    assert validated_report_number("+15559876543") is None       # fictional test number
    assert validated_report_number("01145884697") is None        # junk international prefix
    assert validated_report_number("+442071234567") == "+442071234567"


if __name__ == "__main__":
    main()
