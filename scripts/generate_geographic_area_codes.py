"""Generate the table of geographic area codes outside North America.

An area code here is one libphonenumber reads with
length_of_geographical_area_code and its geocoder names a place for, so
formatting groups that aren't area codes (Uruguay's "2345" inside
Montevideo's 2) and unassigned ranges stay out.

A code is listed when every number sampled under it, including every
two-digit continuation, has that area code, a longer listed one nested in it
(Hornby's 15242 inside Lancaster's 1524; the app reads the longest match), or
no area code at all. A number length where some have none is an exclusion,
which offers nothing: Shanghai's 21 covers its 10-digit lines but not the 7-
and 8-digit shared-cost 96 numbers, and Finland's 8 not the lengths of its
toll-free 800. Dropping a code can break the shorter one that relied on it,
so dropping repeats until nothing changes: Japan's 4 goes with 42, or
+81 42-700-3737 would block all of 04x.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path

import phonenumbers
from phonenumbers import PhoneNumber, PhoneNumberType
from phonenumbers.geodata import GEOCODE_DATA

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = (
    ROOT
    / "app/src/main/java/com/sysadmindoc/callshield/data/areacodes/GeographicAreaCodes.kt"
)
PHONENUMBERS_VERSION = "9.0.41"
NANP = 1
GEOGRAPHIC = {PhoneNumberType.FIXED_LINE, PhoneNumberType.FIXED_LINE_OR_MOBILE}
SEEDS = ("2345678901234567", "9876543210987654")
# Fewer digits after an area code than any real line has; libphonenumber
# validates some such stubs (Germany's 33201 0) without an area code.
SUBSCRIBER_MIN = 4
LINE_WIDTH = 100


def number(cc: int, nsn: str) -> PhoneNumber:
    num = PhoneNumber(country_code=cc, national_number=int(nsn))
    zeros = len(nsn) - len(nsn.lstrip("0"))
    if zeros:
        num.italian_leading_zero = True
        if zeros > 1:
            num.number_of_leading_zeros = zeros
    return num


def lengths_for(cc: int) -> list[int]:
    lengths: set[int] = set()
    for region in phonenumbers.COUNTRY_CODE_TO_REGION_CODE[cc]:
        metadata = phonenumbers.PhoneMetadata.metadata_for_region(region)
        if metadata is None:
            continue
        for desc in (metadata.fixed_line, metadata.mobile, metadata.general_desc):
            if desc is not None and desc.possible_length:
                lengths |= {n for n in desc.possible_length if n > 0}
    return sorted(lengths)


def samples(code: str, lengths: list[int]):
    """Numbers under [code] at each length, each next digit, at least SUBSCRIBER_MIN digits after it."""
    for n in lengths:
        rest = n - len(code)
        if rest < SUBSCRIBER_MIN:
            continue
        for digit in "0123456789":
            for seed in SEEDS:
                yield code + digit + seed[: rest - 1]


def area_code(cc: int, nsn: str) -> str | None:
    """The area code of a valid number, "" for a valid one without, None for an invalid one."""
    num = number(cc, nsn)
    if not phonenumbers.is_valid_number(num):
        return None
    length = phonenumbers.length_of_geographical_area_code(num)
    if length == 0 or phonenumbers.number_type(num) not in GEOGRAPHIC and cc not in phonenumbers.phonenumberutil._GEO_MOBILE_COUNTRIES:
        return ""
    return nsn[:length]


def sweep(code: str, lengths: list[int]):
    """[samples] plus every two-digit continuation, which two seeds per next digit miss (Finland's 800 under 8).

    A one-digit code covers so much that it gets every three-digit continuation
    too: Morocco's VoIP 0592 sits under its landlines' 5.
    """
    yield from samples(code, lengths)
    digits = 3 if len(code) == 1 else 2
    for n in lengths:
        rest = n - len(code)
        if rest < SUBSCRIBER_MIN:
            continue
        for continuation in range(10**digits):
            yield code + f"{continuation:0{digits}d}" + SEEDS[0][: rest - digits]


def found_codes(cc: int, code: str, lengths: list[int]) -> dict[int, set[str]]:
    """The area codes of the valid numbers swept under [code] by number length, "" for one without."""
    found: dict[int, set[str]] = defaultdict(set)
    for nsn in sweep(code, lengths):
        area = area_code(cc, nsn)
        if area is not None:
            found[len(nsn)].add(area)
    return found


def settle(founds: dict[str, dict[int, set[str]]]) -> tuple[list[str], list[str]]:
    """The codes that hold, and as "code@length" the lengths where they also hold numbers without an area code.

    A code holds when every number swept under it has that code, no area code,
    or a longer listed code nested in it, where the app stops looking. Any other
    area code, at any length, means the sample can't place the code's numbers.
    """
    listed = set(founds)
    while True:
        holding = {
            code
            for code in listed
            if all(area in ("", code) or (area.startswith(code) and area in listed) for areas in founds[code].values() for area in areas)
        }
        if holding == listed:
            break
        listed = holding
    excluded = sorted(f"{code}@{n}" for code in listed for n, areas in sorted(founds[code].items()) if "" in areas)
    return sorted(listed), excluded


def generate_codes() -> tuple[dict[int, list[str]], dict[int, list[str]]]:
    places: dict[int, list[str]] = defaultdict(list)
    calling_codes = sorted(phonenumbers.COUNTRY_CODE_TO_REGION_CODE, key=lambda c: -len(str(c)))
    for prefix in GEOCODE_DATA:
        cc = next((c for c in calling_codes if prefix.startswith(str(c))), None)
        if cc is None or cc == NANP or len(prefix) == len(str(cc)):
            continue
        places[cc].append(prefix[len(str(cc)):])

    result: dict[int, list[str]] = {}
    exclusions: dict[int, list[str]] = {}
    for cc, nationals in sorted(places.items()):
        lengths = lengths_for(cc)
        candidates: set[str] = set()
        for national in nationals:
            for nsn in samples(national, lengths):
                found = area_code(cc, nsn)
                if found:
                    # The area code has to fit inside the place's prefix.
                    if len(found) <= len(national):
                        candidates.add(found)
                    break
        kept, excluded = settle({code: found_codes(cc, code, lengths) for code in candidates})
        if kept:
            result[cc] = kept
        if excluded:
            exclusions[cc] = excluded
    return result, exclusions


def kotlin_table(codes: list[str]) -> list[str]:
    text = " " + " ".join(codes) + " "
    chunks: list[str] = []
    while text:
        cut = len(text) if len(text) <= LINE_WIDTH else text.rindex(" ", 0, LINE_WIDTH) + 1
        chunks.append(text[:cut])
        text = text[cut:]
    return chunks


def generate() -> str:
    if phonenumbers.__version__ != PHONENUMBERS_VERSION:
        raise SystemExit(f"Needs phonenumbers {PHONENUMBERS_VERSION}, found {phonenumbers.__version__}")
    tables, exclusions = generate_codes()
    longest = max(len(code) for codes in tables.values() for code in codes)
    total = sum(len(codes) for codes in tables.values())
    excluded = sum(len(codes) for codes in exclusions.values())
    lines = [
        "package com.sysadmindoc.callshield.data.areacodes",
        "",
        f"// Generated by scripts/generate_geographic_area_codes.py from libphonenumber",
        f"// metadata (Python phonenumbers {PHONENUMBERS_VERSION}): {total} area codes in {len(tables)} calling codes,",
        f"// and {excluded} lengths where one of them doesn't apply.",
        "// Don't edit by hand.",
        "",
        "/**",
        " * Geographic area codes outside North America, for the \"Block area code\"",
        " * suggestion: where an area code ends in a national number, which changes",
        " * even inside one country (Berlin is +49 30, Brandenburg an der Havel +49",
        " * 3381). Only codes libphonenumber's geocoder names a place for are listed,",
        " * so mobile ranges and countries without area codes (Spain, Denmark) have",
        " * none.",
        " */",
        "internal object GeographicAreaCodes {",
        "    /**",
        "     * The area code [nationalNumber] starts with under [callingCode], the",
        "     * longest listed one shorter than the number, or null. Null too when",
        "     * that code doesn't cover numbers of this length (Shanghai's 21 and its",
        "     * 7- and 8-digit shared-cost 96 numbers), rather than a shorter code.",
        "     */",
        "    fun areaCode(",
        "        callingCode: String,",
        "        nationalNumber: String,",
        "    ): String? {",
        "        val table = TABLES[callingCode] ?: return null",
        "        for (end in minOf(nationalNumber.length - 1, LONGEST) downTo 1) {",
        "            val prefix = nationalNumber.substring(0, end)",
        "            if (table.contains(\" $prefix \")) {",
        "                val excluded = EXCLUDED[callingCode]?.contains(\" $prefix@${nationalNumber.length} \") == true",
        "                return prefix.takeUnless { excluded }",
        "            }",
        "        }",
        "        return null",
        "    }",
        "",
        f"    private const val LONGEST = {longest}",
        "",
        "    /** Each calling code's area codes, space-separated, with a space at each end. */",
        "    private val TABLES: Map<String, String> =",
        "        mapOf(",
        *kotlin_map(tables),
        "        )",
        "",
        "    /** Each calling code's area codes that don't cover national numbers of one length, as \"code@length\". */",
        "    private val EXCLUDED: Map<String, String> =",
        "        mapOf(",
        *kotlin_map(exclusions),
        "        )",
        "}",
        "",
    ]
    return "\n".join(lines)


def kotlin_map(entries: dict[int, list[str]]) -> list[str]:
    lines: list[str] = []
    for cc, codes in sorted(entries.items(), key=lambda kv: str(kv[0])):
        chunks = kotlin_table(codes)
        if len(chunks) == 1:
            lines.append(f'            "{cc}" to "{chunks[0]}",')
            continue
        lines.append(f'            "{cc}" to')
        for i, chunk in enumerate(chunks):
            sep = " +" if i < len(chunks) - 1 else ","
            lines.append(f'                "{chunk}"{sep}')
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if the generated table differs")
    args = parser.parse_args()
    expected = generate()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_bytes() != expected.encode("utf-8"):
            print(f"{OUTPUT.name} differs from phonenumbers {PHONENUMBERS_VERSION}")
            return 1
        print(f"{OUTPUT.name} matches phonenumbers {PHONENUMBERS_VERSION}.")
        return 0
    OUTPUT.write_text(expected, encoding="utf-8", newline="\n")
    print(f"Wrote {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size} bytes).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
