"""Write the Mendeley SMS phishing set that SmsEvaluationCorpusTest reads.

Source: Sandhya Mishra and Devpriya Soni, "SMS Phishing Dataset for Machine
Learning and Pattern Recognition", Mendeley Data V1 (2022),
https://doi.org/10.17632/f45bkkt8pr.1, CC BY 4.0. The zip is pinned to its
file id and checked against its SHA-256, so the same command always writes the
same file.

It's a second check beside the IMC 2025 sample, from a different collection:
638 smishing, 489 spam and 4,844 clean texts. Most of the spam is UK premium
rate prize and ringtone texts from 2003 to 2012, plus some from India, so it
says whether the rules hold up on scams they weren't written against, not how
they do on current lures. The clean texts are real messages, which the
hand-written clean set isn't.

Every distinct text is kept. Labels are folded to lower case, and a text filed
as both smishing and spam keeps the smishing label. A text filed as both clean
and a scam stops the script, since there's no right answer for it. HTML
entities left in the source (&lt;#&gt;) are decoded. Phone-length digit runs in
clean texts are replaced with 555 numbers of the same length, since those are
people's own numbers. Scam texts keep theirs.

    python scripts/sample_mendeley_sms_corpus.py [--source Dataset_5971.zip]
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import html
import io
import re
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

SOURCE_URL = (
    "https://data.mendeley.com/public-files/datasets/f45bkkt8pr/files/"
    "edb361de-918d-469f-9106-e84823830665/file_downloaded"
)
SOURCE_SHA256 = "9bbf3188fdad81495d8e82825648b9b63b53fc86841a3d26c02629990b233cc3"
MEMBER = "Dataset_5971.csv"
OUTPUT = Path(__file__).resolve().parent.parent / "app/src/test/resources/sms-corpus/mendeley-sms.tsv"

HAM = "ham"
SMISHING = "smishing"
SPAM = "spam"
LABELS = (HAM, SMISHING, SPAM)
PHONE_LENGTH_DIGITS = re.compile(r"\d{7,}")
STAND_IN_DIGITS = "5550100199"


def escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")


def mask_digits(text: str) -> str:
    """Each run of seven or more digits becomes a 555 number of the same length."""

    def stand_in(match: re.Match[str]) -> str:
        length = len(match.group(0))
        return (STAND_IN_DIGITS * (length // len(STAND_IN_DIGITS) + 1))[:length]

    return PHONE_LENGTH_DIGITS.sub(stand_in, text)


def read_source(path: Path | None) -> list[dict[str, str]]:
    if path is None:
        path = Path(tempfile.mkdtemp()) / "Dataset_5971.zip"
        request = urllib.request.Request(SOURCE_URL, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=60) as response:
            path.write_bytes(response.read())
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != SOURCE_SHA256:
        sys.exit(f"{path} has SHA-256 {digest}, expected {SOURCE_SHA256}")
    with zipfile.ZipFile(path) as archive:
        text = archive.read(MEMBER).decode("utf-8")
    rows = list(csv.DictReader(io.StringIO(text, newline="")))
    for index, row in enumerate(rows, start=1):
        row["row"] = str(index)
    return rows


def distinct(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    """One row per text, in source order, with the label rules from the module docstring."""

    kept: dict[str, dict[str, str]] = {}
    for row in rows:
        label = row["LABEL"].strip().lower()
        if label not in LABELS:
            sys.exit(f"row {row['row']} has label {row['LABEL']!r}")
        body = html.unescape(row["TEXT"].strip())
        if not body:
            continue
        earlier = kept.get(body)
        if earlier is None:
            kept[body] = {"row": row["row"], "label": label, "text": body}
        elif HAM in (earlier["label"], label) and earlier["label"] != label:
            sys.exit(f"rows {earlier['row']} and {row['row']} file the same text as {earlier['label']} and {label}")
        elif label == SMISHING:
            earlier["label"] = SMISHING
    for row in kept.values():
        if row["label"] == HAM:
            row["text"] = mask_digits(row["text"])
    return list(kept.values())


def main() -> None:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--source", type=Path, help="a local copy of Dataset_5971.zip")
    parser.add_argument("--output", type=Path, default=OUTPUT, help="where to write the TSV")
    args = parser.parse_args()
    rows = distinct(read_source(args.source))
    lines = ["row\tlabel\ttext"]
    lines.extend("\t".join(escape(field) for field in (row["row"], row["label"], row["text"])) for row in rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
    counts = {label: sum(1 for row in rows if row["label"] == label) for label in LABELS}
    print(f"wrote {len(rows)} rows to {args.output}: {counts}")


if __name__ == "__main__":
    main()
