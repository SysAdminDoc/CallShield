#!/usr/bin/env python3
"""Regression tests for Python report/import phone normalization."""

from phone_normalization import (
    is_plausible_number,
    normalize_nanp_number,
    normalize_phone_number,
    normalize_report_number,
    split_country_code,
    strip_national_trunk_prefix,
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

    assert normalize_nanp_number("212-234-5678") == "+12122345678"
    assert normalize_nanp_number("+442071234567") is None
    # Bulk importers now share the plausibility gate: fictional NANP rows
    # (555 exchange, 555/N11 area codes) are rejected at import time instead
    # of shipping and being purged by the next merge run.
    assert normalize_nanp_number("212-555-1234") is None
    assert normalize_nanp_number("555-234-5678") is None
    assert normalize_nanp_number("211-934-5678") is None

    # Plausibility gating at the report trust boundary.
    assert is_plausible_number("+12122345678") is True          # valid NANP
    assert is_plausible_number("+442071234567") is True          # valid UK
    assert is_plausible_number("+15551234567") is False          # NANP area code 555
    assert is_plausible_number("+12125550101") is False          # 555 exchange (fiction)
    assert is_plausible_number("+12119345678") is False          # N11 area code (211)
    assert is_plausible_number("+01145884697") is False          # leading-zero country code
    assert is_plausible_number("+1234567") is False              # too short

    # Country-code splitting (calling codes are prefix-free).
    assert split_country_code("12122345678") == ("1", "2122345678")
    assert split_country_code("8605586468536") == ("86", "05586468536")
    assert split_country_code("442071234567") == ("44", "2071234567")
    assert split_country_code("35315551234") == ("353", "15551234")   # 3-digit fallback
    assert split_country_code("") is None

    # National trunk prefixes typed into international numbers by hand. The app
    # canonicalizes real calls with PhoneNumberUtils.formatNumberToE164, which
    # never emits the trunk digit, so an un-stripped row could never match.
    assert strip_national_trunk_prefix("8605586468536") == "865586468536"   # China
    assert strip_national_trunk_prefix("4402071234567") == "442071234567"   # UK
    assert strip_national_trunk_prefix("49030123456") == "4930123456"       # Germany
    assert strip_national_trunk_prefix("390612345678") == "390612345678"    # Italy keeps its 0
    assert strip_national_trunk_prefix("2250707123456") == "2250707123456"  # Côte d'Ivoire keeps its 0 (2021 renumbering)
    assert strip_national_trunk_prefix("865586468536") == "865586468536"    # already E.164
    assert strip_national_trunk_prefix("12122345678") == "12122345678"      # NANP untouched

    assert normalize_report_number("+86 0558 646 8536") == "+865586468536"
    assert normalize_report_number("+44 (0)20 7123 4567") == "+442071234567"
    assert normalize_report_number("+39 06 1234 5678") == "+390612345678"
    # Without a leading "+" the country code is unknowable, so leave it alone.
    assert normalize_report_number("2125551234") == "+12125551234"

    assert validated_report_number("212-234-5678") == "+12122345678"
    assert validated_report_number("+15559876543") is None       # fictional test number
    assert validated_report_number("01145884697") is None        # junk international prefix
    assert validated_report_number("+442071234567") == "+442071234567"


if __name__ == "__main__":
    main()
