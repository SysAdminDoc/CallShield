"""Shared phone normalization helpers for report and import scripts."""

FORMAT_CONTROL_CODES = {0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF}


def normalize_phone_number(raw: str | None) -> str:
    if not raw:
        return ""

    cleaned = "".join(ch for ch in str(raw) if ord(ch) not in FORMAT_CONTROL_CODES).strip()
    has_plus = cleaned.startswith("+")
    digits = "".join(ch for ch in cleaned if "0" <= ch <= "9")
    if not digits:
        return ""
    return f"+{digits}" if has_plus else digits


def normalize_report_number(raw: str | None) -> str | None:
    normalized = normalize_phone_number(raw)
    digits = "".join(ch for ch in normalized if "0" <= ch <= "9")
    if len(digits) < 7 or len(digits) > 15:
        return None
    if len(digits) == 10:
        digits = f"1{digits}"
    return f"+{digits}"


def normalize_nanp_number(raw: str | None) -> str | None:
    normalized = normalize_report_number(raw)
    if not normalized:
        return None
    digits = normalized[1:]
    if len(digits) == 11 and digits.startswith("1"):
        return normalized
    return None


def is_plausible_number(e164: str | None) -> bool:
    """Reject fictional, malformed, and structurally invalid report numbers
    before they enter the shared database.

    Expects an E.164 string ("+digits") as produced by normalize_report_number.
    Kills the classes seen polluting the community stream: fictional NANP
    (area/exchange 555, N11 service codes), leading-zero "country codes"
    (`+0…`, e.g. an international-dialing `011…` prefix left in), and
    implausibly short numbers.
    """
    if not e164 or not e164.startswith("+"):
        return False
    digits = e164[1:]
    if not digits.isdigit():
        return False
    if not 8 <= len(digits) <= 15:
        return False
    if digits[0] == "0":  # no country calling code starts with 0
        return False
    if digits[0] == "1":  # NANP
        if len(digits) != 11:
            return False
        npa, nxx = digits[1:4], digits[4:7]
        # Area code: N[0-9][0-9]; not an N11 service code, not the 555 pseudo-code.
        if npa[0] < "2" or npa[1:] == "11" or npa == "555":
            return False
        # Exchange: N[0-9][0-9]; 555 is reserved for fiction/directory assistance.
        if nxx[0] < "2" or nxx == "555":
            return False
    return True


def validated_report_number(raw: str | None) -> str | None:
    """Normalize and plausibility-check a community report number.

    Returns the E.164 form, or None for junk/fictional/malformed input.
    Use this (not normalize_report_number) at the trust boundary where
    anonymous reports enter the shipped database.
    """
    normalized = normalize_report_number(raw)
    if normalized and is_plausible_number(normalized):
        return normalized
    return None
