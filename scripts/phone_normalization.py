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
