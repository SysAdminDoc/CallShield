"""Generate the on-device area-code table from the pinned NANP snapshot."""

from __future__ import annotations

import argparse
import csv
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "data/nanp-area-codes.csv"
LABELS = ROOT / "data/area-code-city-labels.csv"
OUTPUT = (
    ROOT
    / "app/src/main/java/com/sysadmindoc/callshield/data/areacodes/AreaCodeLookup.kt"
)
UPSTREAM_COMMIT = "9d909f4d47d59d8eec6dbdaa66257682670f3e4c"
SOURCE_SHA256 = "a8da408ca00254dc9b668c765fdf6b6f3f914b416f5a416c46ffafd2dd53d259"

CANADIAN_REGIONS = {
    "ALBERTA": "AB",
    "BRITISH COLUMBIA": "BC",
    "MANITOBA": "MB",
    "NEW BRUNSWICK": "NB",
    "NEWFOUNDLAND AND LABRADOR": "NL",
    "NOVA SCOTIA - PRINCE EDWARD ISLAND": "NS",
    "NORTHWEST TERRITORIES -YUKON - NUNAVUT": "NT",
    "ONTARIO": "ON",
    "QUEBEC": "QC",
    "SASKATCHEWAN": "SK",
}
SHARED_CANADIAN_REGIONS = {
    "NOVA SCOTIA - PRINCE EDWARD ISLAND": ("NS", "PE"),
    "NORTHWEST TERRITORIES -YUKON - NUNAVUT": ("NT", "YT", "NU"),
}
TERRITORIES = {
    "AS": "American Samoa",
    "CNMI": "Northern Mariana Islands",
    "GU": "Guam",
    "PR": "Puerto Rico",
    "VI": "US Virgin Islands",
}


def load_rows() -> list[dict[str, str]]:
    actual_hash = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    if actual_hash != SOURCE_SHA256:
        raise ValueError(f"NANP snapshot hash changed: {actual_hash}")
    with SOURCE.open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(line for line in source if not line.startswith("#")))
    if len(rows) != 800 or len({row["npa"] for row in rows}) != len(rows):
        raise ValueError("NANP snapshot has unexpected or duplicate rows")
    return rows


def load_labels() -> dict[str, str]:
    with LABELS.open(encoding="utf-8", newline="") as source:
        labels = {row["npa"]: row["label"] for row in csv.DictReader(source)}
    if len(labels) < 350:
        raise ValueError("City label file is incomplete")
    return labels


def region_code(row: dict[str, str]) -> str | None:
    if row["country"] == "US":
        return "MP" if row["region"] == "CNMI" else row["region"]
    if row["country"] == "CANADA":
        return CANADIAN_REGIONS[row["region"]]
    return None


def display_label(
    row: dict[str, str], labels: dict[str, str], active: dict[str, dict[str, str]]
) -> str:
    code = region_code(row)
    npa = row["npa"]
    label = labels.get(npa)
    if label and (
        code is None or label.endswith(f", {code}") or label == row["region"].title()
    ):
        return label
    parent = row["parent_npa"]
    parent_label = labels.get(parent)
    if (
        parent in active
        and parent_label
        and code
        and parent_label.endswith(f", {code}")
    ):
        return parent_label
    if row["region"] in TERRITORIES:
        return f"{TERRITORIES[row['region']]}, {code}"
    if row["country"] == "CANADA":
        return f"{row['region'].split(' - ')[0].title()}, {code}"
    return f"{row['region'].title()}, {code}" if code else row["region"].title()


def generate() -> str:
    rows = load_rows()
    labels = load_labels()
    geographic = {
        row["npa"]: row
        for row in rows
        if row["kind"] == "geographic" and row["status"] == "in_service"
    }
    toll_free = {
        row["npa"]: row
        for row in rows
        if row["kind"] == "toll_free" and row["status"] == "in_service"
    }
    if len(geographic) != 453 or len(toll_free) != 7:
        raise ValueError("Pinned NANP snapshot has unexpected active-code counts")
    lines = [
        "package com.sysadmindoc.callshield.data.areacodes",
        "",
        "import com.sysadmindoc.callshield.data.RegionCallingCodes",
        "import com.sysadmindoc.callshield.util.filterAsciiDigits",
        "",
        "/** In-service NANP geographic and toll-free codes, generated from NumberResearch.org. */",
        "object AreaCodeLookup {",
        "    private data class AreaCode(",
        "        val label: String,",
        "        val regionCode: String?,",
        "    )",
        "",
        "    /**",
        "     * The place name for [number]'s area code, or null. [homeRegionIso] is the",
        "     * phone's home region, and it has no default so no caller can forget it: a",
        "     * number without a `+` reads as North American only when that region uses",
        "     * +1 or is unknown. On a phone from China, 130 1234 5678 is a mobile number,",
        "     * not Rockville, MD.",
        "     */",
        "    fun lookup(",
        "        number: String,",
        "        homeRegionIso: String?,",
        "    ): String? = getAreaCode(number, homeRegionIso)?.let { AREA_CODES[it]?.label }",
        "",
        "    fun getRegionCode(number: String): String? = getAreaCode(number)?.let { AREA_CODES[it]?.regionCode }",
        "",
        "    fun getRegionCodes(number: String): Set<String> {",
        "        val areaCode = getAreaCode(number) ?: return emptySet()",
        "        return SHARED_REGIONS[areaCode] ?: AREA_CODES[areaCode]?.regionCode?.let { setOf(it) } ?: emptySet()",
        "    }",
        "",
        "    /**",
        "     * Whether [number]'s area code is missing from the table but could have",
        "     * entered service since the snapshot: a geographic or toll-free code that",
        "     * was unassigned, reserved, planned or suspended. Region rules let those",
        "     * through rather than block a real new code. N11, 555, non-geographic codes",
        "     * such as 5XX, 600 and 700, and premium 900 have no region at all.",
        "     */",
        "    fun mayBeNewAreaCode(number: String): Boolean = getAreaCode(number)?.let { it in NOT_YET_IN_SERVICE } == true",
        "",
        "    /**",
        "     * The area code of a North American [number], or null. A `+` number is",
        "     * North American only as +1 and ten digits, however it's spaced: +65 9123",
        "     * 4567 is not area code 659 (Birmingham, AL), and + 1 415 555 0123 is still",
        "     * 415. A bare number follows [homeRegionIso] as in [lookup]; region rules",
        "     * check the home region themselves (RegionRules.regionCode).",
        "     */",
        "    fun getAreaCode(",
        "        number: String,",
        "        homeRegionIso: String? = null,",
        "    ): String? {",
        "        val digits = filterAsciiDigits(number)",
        '        if (number.trimStart().startsWith("+")) {',
        '            return digits.takeIf { it.length == 11 && it.startsWith("1") }?.substring(1, 4)',
        "        }",
        '        if (RegionCallingCodes.forRegion(homeRegionIso)?.let { it != "1" } == true) return null',
        "        return when {",
        '            digits.length == 11 && digits.startsWith("1") -> digits.substring(1, 4)',
        "            digits.length == 10 -> digits.substring(0, 3)",
        "            else -> null",
        "        }",
        "    }",
        "",
        f"    // nanp-data commit {UPSTREAM_COMMIT}; NANPA source file dated 2026-09-17.",
        "    private val AREA_CODES =",
        "        mapOf(",
    ]
    for npa, row in sorted(geographic.items()):
        label = display_label(row, labels, geographic)
        code = region_code(row)
        kotlin_code = f'"{code}"' if code else "null"
        lines.append(f'            "{npa}" to AreaCode("{label}", {kotlin_code}),')
    for npa in sorted(toll_free):
        lines.append(f'            "{npa}" to AreaCode("Toll-Free", "TF"),')
    lines.extend(
        ["        )", "", "    private val SHARED_REGIONS =", "        mapOf("]
    )
    for npa, row in sorted(geographic.items()):
        shared = SHARED_CANADIAN_REGIONS.get(row["region"])
        if shared:
            codes = ", ".join(f'"{code}"' for code in shared)
            lines.append(f'            "{npa}" to setOf({codes}),')
    lines.extend(["        )", "", "    private val NOT_YET_IN_SERVICE =", "        setOf("])
    for row in sorted(rows, key=lambda row: row["npa"]):
        if row["kind"] in ("geographic", "toll_free") and row["status"] != "in_service":
            lines.append(f'            "{row["npa"]}",')
    lines.extend(["        )", "}", ""])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="Fail if the generated table differs"
    )
    args = parser.parse_args()
    expected = generate()
    if args.check:
        if OUTPUT.read_bytes() != expected.encode("utf-8"):
            print(f"Area-code table differs from {SOURCE.relative_to(ROOT)}")
            return 1
        print(
            "Area-code table matches pinned NANP snapshot (453 geographic, 7 toll-free)."
        )
        return 0
    OUTPUT.write_text(expected, encoding="utf-8", newline="\n")
    print("Generated 453 geographic and 7 toll-free area codes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
