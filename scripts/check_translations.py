#!/usr/bin/env python3
"""Validate CallShield translation resources against the English source.

CallShield takes translations as community contributions, so the review has to
catch the mistakes that are invisible in a diff and expensive at runtime:

* a format specifier that changed arity or type - `String.format` throws
  `IllegalFormatConversionException` at the moment the string is shown, which
  for this app can mean the call-screening notification, on a device whose
  language the maintainer does not read;
* positional vs implicit arguments - Android requires positional (`%1$s`) once a
  string has more than one argument, and a translation that reorders implicit
  ones silently swaps the values;
* a plural that is missing the quantities its language actually needs;
* keys that no longer exist upstream, which quietly rot;
* a translated string that upstream marked `translatable="false"`.

Usage:
    python scripts/check_translations.py                  # every values-* locale
    python scripts/check_translations.py --locale zh-rCN  # one locale
    python scripts/check_translations.py --dir path/to/values-zh-rCN
    python scripts/check_translations.py --coverage-only  # report, never fail

Exits non-zero when a translation has an error. Missing keys are reported as
coverage, not failure: partial translations are explicitly welcome, and Android
falls back to English per string.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES_DIR = ROOT / "app" / "src" / "main" / "res"
BASE_DIR = RES_DIR / "values"

# %[argument_index$][flags][width][.precision]conversion
FORMAT_RE = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*)(\d+)?(?:\.\d+)?([a-zA-Z%])")

# CLDR plural categories that must be present per language. Languages absent
# here are only required to provide "other"; listing every language would be
# noise, and an extra category is never an error.
REQUIRED_PLURAL_QUANTITIES = {
    "en": {"one", "other"},
    "de": {"one", "other"},
    "es": {"one", "other"},
    "fr": {"one", "other"},
    "it": {"one", "other"},
    "nl": {"one", "other"},
    "pt": {"one", "other"},
    "ru": {"one", "few", "many", "other"},
    "pl": {"one", "few", "many", "other"},
    "ar": {"zero", "one", "two", "few", "many", "other"},
    # zh/ja/ko/th/vi have no grammatical plural: "other" alone is correct.
}
DEFAULT_PLURAL_QUANTITIES = {"other"}

# A values-* directory is a LOCALE only when its qualifier is a language code:
# "values-zh-rCN", "values-de", or BCP-47 "values-b+zh+Hans". Other qualifiers
# on the same prefix ("values-v31", "values-night", "values-land") are not
# translations and must not be reported as untranslated locales.
LOCALE_DIR_RE = re.compile(r"^values-(?:b\+[A-Za-z0-9+]+|[a-z]{2,3}(?:-r[A-Z]{2})?)$")


def specifiers(text: str) -> list[str]:
    """Return the normalized format specifiers in a string, in argument order.

    Normalized to `index:conversion` so that flags and width - which a
    translator may legitimately adjust - do not register as a mismatch, while a
    changed type or argument count does.
    """
    found = []
    implicit = 0
    for match in FORMAT_RE.finditer(text):
        index, _flags, _width, conversion = match.groups()
        if conversion == "%":  # literal %% - not an argument
            continue
        if index is None:
            implicit += 1
            position = implicit
        else:
            position = int(index)
        found.append(f"{position}:{conversion.lower()}")
    return sorted(set(found))


def resource_files(values_dir: Path) -> list[Path]:
    """Every resource XML in a values directory.

    Filenames carry no meaning to aapt: this repo keeps its <plurals> inside
    values/strings.xml while the zh-rCN contribution split them into a separate
    plurals.xml. Both are valid, so scan the directory rather than fixed names.
    """
    if not values_dir.is_dir():
        return []
    return sorted(values_dir.glob("*.xml"))


def load_strings(values_dir: Path) -> tuple[dict[str, str], set[str]]:
    """Return {name: text} plus the names marked translatable="false"."""
    values: dict[str, str] = {}
    untranslatable: set[str] = set()
    for path in resource_files(values_dir):
        root = ET.parse(path).getroot()
        if root.tag != "resources":
            continue
        for node in root.findall("string"):
            name = node.get("name")
            if not name:
                continue
            if node.get("translatable") == "false":
                untranslatable.add(name)
            values[name] = "".join(node.itertext())
    return values, untranslatable


def load_plurals(values_dir: Path) -> dict[str, dict[str, str]]:
    plurals: dict[str, dict[str, str]] = {}
    for path in resource_files(values_dir):
        root = ET.parse(path).getroot()
        if root.tag != "resources":
            continue
        for node in root.findall("plurals"):
            name = node.get("name")
            if not name:
                continue
            items = {}
            for item in node.findall("item"):
                quantity = item.get("quantity")
                if quantity:
                    items[quantity] = "".join(item.itertext())
            plurals[name] = items
    return plurals


def language_of(locale: str) -> str:
    return locale.split("-")[0].lower()


def bcp47_of(resource_locale: str) -> str:
    """`zh-rCN` (resource qualifier) -> `zh-CN` (BCP-47, what locales_config uses)."""
    parts = resource_locale.split("-")
    if len(parts) == 2 and parts[1].startswith("r") and len(parts[1]) == 3:
        return f"{parts[0]}-{parts[1][1:]}"
    return resource_locale


def check_locales_config(locale_dirs: list[Path]) -> list[str]:
    """Every shipped locale must be declared in res/xml/locales_config.xml.

    The manifest sets android:localeConfig, so this file is what populates the
    per-app language picker in system settings. A translation that ships
    resources without being listed here is invisible: the user has no way to
    select it, and it only appears if their whole device is set to that
    language. Easy to miss in review, so it is checked mechanically.
    """
    config_path = RES_DIR / "xml" / "locales_config.xml"
    if not config_path.is_file():
        return []

    declared = {
        node.get("{http://schemas.android.com/apk/res/android}name")
        for node in ET.parse(config_path).getroot().findall("locale")
    }
    declared.discard(None)

    errors = []
    for locale_dir in locale_dirs:
        locale = bcp47_of(locale_dir.name.removeprefix("values-"))
        # A language-only declaration ("zh") also covers a region variant.
        if locale not in declared and language_of(locale) not in declared:
            errors.append(
                f"{locale_dir.name} ships translations but '{locale}' is not declared in "
                f"res/xml/locales_config.xml - the per-app language picker will not offer it"
            )
    return errors


def check_locale(locale_dir: Path, base_strings, base_untranslatable, base_plurals):
    """Return (errors, warnings, translated_count, base_count)."""
    locale = locale_dir.name.removeprefix("values-")
    errors: list[str] = []
    warnings: list[str] = []

    try:
        strings, _ = load_strings(locale_dir)
        plurals = load_plurals(locale_dir)
    except ET.ParseError as e:
        return [f"{locale}: XML does not parse: {e}"], [], 0, len(base_strings)

    translatable_base = {k: v for k, v in base_strings.items() if k not in base_untranslatable}

    for name, text in strings.items():
        if name not in base_strings:
            warnings.append(f"{locale}: '{name}' no longer exists in values/strings.xml (stale key)")
            continue
        if name in base_untranslatable and text != base_strings[name]:
            warnings.append(f"{locale}: '{name}' is marked translatable=\"false\" upstream but was translated")
            continue

        want = specifiers(base_strings[name])
        got = specifiers(text)
        if want != got:
            errors.append(
                f"{locale}: '{name}' format specifiers differ - English has {want or '[]'}, "
                f"translation has {got or '[]'} (String.format would throw at display time)"
            )

    required = REQUIRED_PLURAL_QUANTITIES.get(language_of(locale), DEFAULT_PLURAL_QUANTITIES)
    for name, items in plurals.items():
        if name not in base_plurals:
            warnings.append(f"{locale}: plural '{name}' no longer exists upstream (stale key)")
            continue
        missing = required - set(items)
        if missing:
            errors.append(f"{locale}: plural '{name}' is missing required quantities {sorted(missing)}")
        for quantity, text in items.items():
            base_item = base_plurals[name].get(quantity) or base_plurals[name].get("other", "")
            want, got = specifiers(base_item), specifiers(text)
            if want != got:
                errors.append(
                    f"{locale}: plural '{name}' [{quantity}] format specifiers differ - "
                    f"English has {want or '[]'}, translation has {got or '[]'}"
                )

    translated = len(set(strings) & set(translatable_base))
    return errors, warnings, translated, len(translatable_base)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--locale", help="Check a single locale, e.g. zh-rCN")
    parser.add_argument("--dir", type=Path, help="Check an arbitrary values-* directory (e.g. a fork's, before merging)")
    parser.add_argument("--coverage-only", action="store_true", help="Report coverage but always exit 0")
    args = parser.parse_args()

    base_strings, base_untranslatable = load_strings(BASE_DIR)
    base_plurals = load_plurals(BASE_DIR)
    if not base_strings:
        print(f"error: no base strings found in {BASE_DIR}", file=sys.stderr)
        return 2

    if args.dir:
        locale_dirs = [args.dir]
    elif args.locale:
        locale_dirs = [RES_DIR / f"values-{args.locale}"]
    else:
        locale_dirs = sorted(
            d for d in RES_DIR.glob("values-*")
            if d.is_dir() and resource_files(d) and LOCALE_DIR_RE.match(d.name)
        )

    if not locale_dirs:
        print("No translation locales present - nothing to check.")
        print("Translations are welcome: see docs/TRANSLATING.md.")
        return 0

    total_errors = 0
    for locale_dir in locale_dirs:
        if not locale_dir.is_dir():
            print(f"error: {locale_dir} does not exist", file=sys.stderr)
            return 2
        errors, warnings, translated, total = check_locale(
            locale_dir, base_strings, base_untranslatable, base_plurals
        )
        percent = (translated / total * 100) if total else 0.0
        status = "FAIL" if errors else "ok"
        print(f"[{status}] {locale_dir.name}: {translated}/{total} strings ({percent:.1f}%)")
        for warning in warnings:
            print(f"  warning: {warning}")
        for error in errors:
            print(f"  ERROR:   {error}")
        total_errors += len(errors)

    # Only meaningful for locales that actually live in the repo.
    for error in check_locales_config([d for d in locale_dirs if d.parent == RES_DIR]):
        print(f"  ERROR:   {error}")
        total_errors += 1

    if total_errors and not args.coverage_only:
        print(f"\n{total_errors} translation error(s).", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
