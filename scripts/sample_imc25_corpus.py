"""Draw the stratified IMC 2025 smishing sample that SmsEvaluationCorpusTest reads.

Source: Agarwal, Papasavva, Suarez-Tangil and Vasek, "Fishing for Smishing"
(IMC 2025), https://github.com/reportsmishing/Smishing-Dataset-IMC25, CC BY 4.0.
The file is pinned to one commit and checked against its SHA-256, so the same
command always writes the same sample.

Every reply-based scam (wrong number, hey mum/dad) is kept, since those are the
rows the reply-bait rule is measured on. The rest of the budget goes to each
language in proportion to the square root of its size, with a floor, so small
languages get enough rows to measure and English doesn't swamp the sample.
Duplicate texts are dropped first. Texts are copied unchanged; the test swaps
the dataset's <URL>-style placeholders for neutral stand-ins when it reads them.

    python scripts/sample_imc25_corpus.py [--source final_dataset_output.csv]
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import math
import random
import sys
import tempfile
import urllib.request
from collections import defaultdict
from pathlib import Path

COMMIT = "a6175560b57387199871e51fbef6bc523d2516b4"
SOURCE_URL = f"https://raw.githubusercontent.com/reportsmishing/Smishing-Dataset-IMC25/{COMMIT}/dataset/final_dataset_output.csv"
SOURCE_SHA256 = "1bbd1e9e82c3ea023112207b80da268a5c4a07d2353c2b0898360ab037fa9a64"
OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "app/src/test/resources/sms-corpus/imc25-sample.tsv"
)

BUDGET = 5000
FLOOR = 40
SEED = 20260930
REPLY_BASED = {"wrong number", "hey mum/dad"}

LANGUAGE_TAGS = {
    "Afrikaans": "af",
    "Albanian": "sq",
    "Arabic": "ar",
    "Bengali": "bn",
    "Bulgarian": "bg",
    "Catalan": "ca",
    "Chinese": "zh",
    "Croatian": "hr",
    "Czech": "cs",
    "Danish": "da",
    "Divehi": "dv",
    "Dutch": "nl",
    "English": "en",
    "Estonian": "et",
    "Filipino": "tl",
    "Finnish": "fi",
    "French": "fr",
    "Ganda": "lg",
    "German": "de",
    "Greek": "el",
    "Gujarati": "gu",
    "Hebrew": "he",
    "Hindi": "hi",
    "Hungarian": "hu",
    "Icelandic": "is",
    "Igbo": "ig",
    "Indonesian": "id",
    "Italian": "it",
    "Japanese": "ja",
    "Javanese": "jv",
    "Kannada": "kn",
    "Kinyarwanda": "rw",
    "Korean": "ko",
    "Latvian": "lv",
    "Lithuanian": "lt",
    "Malay": "ms",
    "Maltese": "mt",
    "Marathi": "mr",
    "Mongolian": "mn",
    "Norwegian": "no",
    "Persian": "fa",
    "Polish": "pl",
    "Portuguese": "pt",
    "Punjabi": "pa",
    "Romanian": "ro",
    "Russian": "ru",
    "Serbian": "sr",
    "Sinhala": "si",
    "Slovak": "sk",
    "Slovenian": "sl",
    "Spanish": "es",
    "Swahili": "sw",
    "Swedish": "sv",
    "Tagalog": "tl",
    "Tamil": "ta",
    "Telugu": "te",
    "Thai": "th",
    "Turkish": "tr",
    "Ukrainian": "uk",
    "Urdu": "ur",
    "Uzbek": "uz",
    "Vietnamese": "vi",
    "Welsh": "cy",
    "Yoruba": "yo",
    "Zulu": "zu",
}
SENDERS = {"phone number": "phone", "alphanumeric": "alpha", "email": "email"}


def language_tag(name: str) -> str:
    """The dataset's language names as BCP 47 tags; blank, unknown and mixed rows are "und"."""
    return LANGUAGE_TAGS.get(name.strip(), "und")


def escape(text: str) -> str:
    return (
        text.replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    )


def read_source(path: Path | None) -> list[dict[str, str]]:
    if path is None:
        path = Path(tempfile.mkdtemp()) / "final_dataset_output.csv"
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            path.write_bytes(response.read())
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != SOURCE_SHA256:
        sys.exit(f"{path} has SHA-256 {digest}, expected {SOURCE_SHA256}")
    csv.field_size_limit(1 << 30)
    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    for index, row in enumerate(rows, start=1):
        row["row"] = str(index)
    return rows


def distinct(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    seen: set[str] = set()
    kept = []
    for row in rows:
        text = row["text"].strip()
        if text and text not in seen:
            seen.add(text)
            kept.append(row)
    return kept


def allocate(sizes: dict[str, int], budget: int) -> dict[str, int]:
    """Square-root shares with a floor, capped at each language's size."""
    quota = {language: min(size, FLOOR) for language, size in sizes.items()}
    left = budget - sum(quota.values())
    while left > 0:
        open_languages = {
            language: size for language, size in sizes.items() if quota[language] < size
        }
        if not open_languages:
            break
        weight = sum(math.sqrt(size) for size in open_languages.values())
        added = 0
        for language, size in sorted(open_languages.items()):
            share = int(left * math.sqrt(size) / weight)
            grant = min(share, size - quota[language])
            quota[language] += grant
            added += grant
        if added == 0:
            for language in sorted(
                open_languages, key=lambda name: -open_languages[name]
            )[:left]:
                quota[language] += 1
                added += 1
        left -= added
    return quota


def sample(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    rng = random.Random(SEED)
    rows = distinct(rows)
    forced = [row for row in rows if row["scam_type"] in REPLY_BASED]
    rest = defaultdict(list)
    for row in rows:
        if row["scam_type"] not in REPLY_BASED:
            rest[language_tag(row["language"])].append(row)
    quota = allocate(
        {language: len(group) for language, group in rest.items()}, BUDGET - len(forced)
    )
    picked = list(forced)
    for language in sorted(rest):
        group = rest[language]
        by_type = defaultdict(list)
        for row in group:
            by_type[row["scam_type"]].append(row)
        # Within a language, each scam type gets its share of the quota.
        want = quota[language]
        taken = []
        for scam_type in sorted(by_type):
            members = by_type[scam_type]
            share = round(want * len(members) / len(group))
            taken.extend(rng.sample(members, min(share, len(members))))
        chosen = {row["row"] for row in taken}
        leftover = [row for row in group if row["row"] not in chosen]
        rng.shuffle(leftover)
        taken = taken[:want] + leftover[: max(0, want - len(taken))]
        picked.extend(taken)
    return sorted(picked, key=lambda row: int(row["row"]))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--source", type=Path, help="a local copy of final_dataset_output.csv"
    )
    args = parser.parse_args()
    picked = sample(read_source(args.source))
    assert len(picked) <= BUDGET, len(picked)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    lines = ["row\tlanguage\tscam_type\tsender\tshortener\ttext"]
    for row in picked:
        fields = [
            row["row"],
            language_tag(row["language"]),
            row["scam_type"].strip() or "unlabeled",
            SENDERS.get(row["sender_id"].strip(), "unknown"),
            row["url_shortener"].strip(),
            row["text"].strip(),
        ]
        lines.append("\t".join(escape(field) for field in fields))
    OUTPUT.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
    print(f"wrote {len(picked)} rows to {OUTPUT}")


if __name__ == "__main__":
    main()
