#!/usr/bin/env python3
"""Audit release metadata, dependency locks, advisories, and source provenance.

This is intentionally a repository-local check. It does not contact release
services, print credentials, or infer that an old dependency is current merely
because a build succeeds. Known advisory dispositions are checked in alongside
the code and must be explicit.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
MAX_SNAPSHOT_AGE_DAYS = 30
SOURCE_FIELDS = (
    "access_mode",
    "geography",
    "license",
    "attribution",
    "cadence",
    "parser_version",
    "redistributable",
    "stale_after_days",
    "evidence_type",
    "confidence_tier",
)
ADVISORY_DISPOSITIONS = {"resolved", "mitigated-build-cache", "accepted-risk"}


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def parse_app_version(text: str) -> tuple[str, int]:
    name_match = re.search(r"(?m)^\s*versionName\s*=\s*\"([^\"]+)\"\s*$", text)
    code_match = re.search(r"(?m)^\s*versionCode\s*=\s*(\d+)\s*$", text)
    if not name_match or not code_match:
        raise ValueError("app/build.gradle.kts must declare versionName and versionCode")
    return name_match.group(1), int(code_match.group(1))


def version_key(value: str) -> tuple[int, ...]:
    match = re.fullmatch(r"\d+(?:\.\d+)+", value.strip())
    if not match:
        raise ValueError(f"unsupported numeric version: {value}")
    return tuple(int(part) for part in value.split("."))


def parse_versions_catalog(text: str) -> dict[str, str]:
    section = re.search(r"(?ms)^\[versions\]\s*\n(.*?)(?=^\[|\Z)", text)
    if not section:
        raise ValueError("gradle/libs.versions.toml has no [versions] section")
    versions: dict[str, str] = {}
    for line in section.group(1).splitlines():
        match = re.match(r"^\s*([A-Za-z0-9_-]+)\s*=\s*\"([^\"]+)\"\s*(?:#.*)?$", line)
        if match:
            versions[match.group(1)] = match.group(2)
    return versions


def parse_library_catalog(text: str) -> dict[str, dict[str, str | None]]:
    section = re.search(r"(?ms)^\[libraries\]\s*\n(.*?)(?=^\[|\Z)", text)
    if not section:
        raise ValueError("gradle/libs.versions.toml has no [libraries] section")
    libraries: dict[str, dict[str, str | None]] = {}
    for line in section.group(1).splitlines():
        match = re.match(r"^\s*([A-Za-z0-9_-]+)\s*=\s*\{(.*)\}\s*(?:#.*)?$", line)
        if not match:
            continue
        body = match.group(2)
        group = re.search(r"\bgroup\s*=\s*\"([^\"]+)\"", body)
        name = re.search(r"\bname\s*=\s*\"([^\"]+)\"", body)
        version_ref = re.search(r"\bversion\.ref\s*=\s*\"([^\"]+)\"", body)
        version = re.search(r"\bversion\s*=\s*\"([^\"]+)\"", body)
        if group and name:
            libraries[match.group(1)] = {
                "group": group.group(1),
                "name": name.group(1),
                "version_ref": version_ref.group(1) if version_ref else None,
                "version": version.group(1) if version else None,
            }
    return libraries


def parse_lockfile(text: str) -> set[tuple[str, str, str]]:
    coordinates: set[tuple[str, str, str]] = set()
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = re.match(r"^([^:]+):([^:]+):([^=]+)=", line)
        if match:
            coordinates.add((match.group(1), match.group(2), match.group(3)))
    return coordinates


def dependency_audit(root: Path) -> tuple[dict[str, Any], list[str]]:
    issues: list[str] = []
    catalog_path = root / "gradle/libs.versions.toml"
    lock_path = root / "app/gradle.lockfile"
    app_build_path = root / "app/build.gradle.kts"
    wrapper_path = root / "gradle/wrapper/gradle-wrapper.properties"
    report: dict[str, Any] = {"versions": {}, "wrapper": None, "locked_direct_libraries": 0}

    for path in (catalog_path, lock_path, app_build_path, wrapper_path):
        if not path.is_file():
            issues.append(f"Missing dependency verification input: {path.relative_to(root)}")
    if issues:
        return report, issues

    try:
        catalog_text = read_text(catalog_path)
        versions = parse_versions_catalog(catalog_text)
        libraries = parse_library_catalog(catalog_text)
        lock_coordinates = parse_lockfile(read_text(lock_path))
    except (OSError, ValueError) as error:
        return report, [f"Dependency metadata could not be parsed: {error}"]

    report["versions"] = dict(sorted(versions.items()))
    report["lock_entries"] = len(lock_coordinates)
    if not lock_coordinates:
        issues.append("app/gradle.lockfile contains no dependency coordinates.")

    dynamic_entries = sorted(
        coordinate
        for coordinate in lock_coordinates
        if any(token in coordinate[2].lower() for token in ("+", "latest", "snapshot"))
    )
    if dynamic_entries:
        issues.append("Dependency lock contains dynamic versions: " + ", ".join(": ".join(item) for item in dynamic_entries))

    app_text = read_text(app_build_path)
    used_accessors = sorted(
        accessor
        for accessor in set(re.findall(r"\blibs\.([A-Za-z0-9_.]+)", app_text))
        if not accessor.startswith(("plugins.", "versions."))
    )
    for accessor in used_accessors:
        key = accessor.replace(".", "-")
        library = libraries.get(key)
        if library is None:
            issues.append(f"app/build.gradle.kts references catalog alias libs.{accessor}, but it is not declared.")
            continue
        coordinate_prefix = (library["group"], library["name"])
        matching = [coordinate for coordinate in lock_coordinates if coordinate[:2] == coordinate_prefix]
        if not matching:
            issues.append(f"Locked dependency is missing for libs.{accessor} ({library['group']}:{library['name']}).")
            continue
        expected_version = None
        if library["version_ref"]:
            expected_version = versions.get(str(library["version_ref"]))
            if expected_version is None:
                issues.append(f"Catalog alias {key} references missing version key {library['version_ref']}.")
        elif library["version"]:
            expected_version = str(library["version"])
        if expected_version and (library["group"], library["name"], expected_version) not in lock_coordinates:
            issues.append(
                f"Lock drift for libs.{accessor}: expected {library['group']}:{library['name']}:{expected_version}."
            )
        report["locked_direct_libraries"] += 1

    wrapper_text = read_text(wrapper_path)
    wrapper_match = re.search(r"distributionUrl=.*gradle-([0-9]+(?:\.[0-9]+)+)-bin\.zip", wrapper_text)
    checksum_match = re.search(r"(?m)^distributionSha256Sum=([0-9a-f]{64})$", wrapper_text)
    if not wrapper_match:
        issues.append("Gradle wrapper distribution URL is not pinned to a numeric version.")
    if not checksum_match:
        issues.append("Gradle wrapper distributionSha256Sum is missing or malformed.")
    if wrapper_match:
        report["wrapper"] = wrapper_match.group(1)
    return report, issues


def parse_iso_timestamp(value: Any) -> datetime:
    if not isinstance(value, str) or not value:
        raise ValueError("generated_at must be an ISO-8601 string")
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        raise ValueError("generated_at must include a timezone")
    return parsed.astimezone(timezone.utc)


def source_audit(
    root: Path,
    now: datetime,
    max_age_days: int = MAX_SNAPSHOT_AGE_DAYS,
) -> tuple[dict[str, Any], list[str]]:
    issues: list[str] = []
    manifest_path = root / "data/source-manifest.json"
    snapshot_path = root / "data/source-snapshot.json"
    report: dict[str, Any] = {"generated_at": None, "source_count": 0, "statuses": {}}
    if not manifest_path.is_file() or not snapshot_path.is_file():
        missing = [str(path.relative_to(root)) for path in (manifest_path, snapshot_path) if not path.is_file()]
        return report, [f"Missing source provenance input: {', '.join(missing)}"]
    try:
        manifest = json.loads(read_text(manifest_path))
        snapshot = json.loads(read_text(snapshot_path))
    except (OSError, json.JSONDecodeError) as error:
        return report, [f"Source provenance could not be parsed: {error}"]

    manifest_sources = manifest.get("sources")
    snapshot_sources = snapshot.get("sources")
    if not isinstance(manifest_sources, list) or not isinstance(snapshot_sources, list):
        return report, ["Source manifest and snapshot must contain source arrays."]
    manifest_by_id = {source.get("id"): source for source in manifest_sources if isinstance(source, dict)}
    snapshot_by_id = {source.get("id"): source for source in snapshot_sources if isinstance(source, dict)}
    if len(manifest_by_id) != len(manifest_sources):
        issues.append("Source manifest contains missing or duplicate source IDs.")
    if len(snapshot_by_id) != len(snapshot_sources):
        issues.append("Source snapshot contains missing or duplicate source IDs.")
    if set(manifest_by_id) != set(snapshot_by_id):
        issues.append(
            "Source snapshot IDs do not match the manifest: "
            f"missing={sorted(set(manifest_by_id) - set(snapshot_by_id))}, "
            f"extra={sorted(set(snapshot_by_id) - set(manifest_by_id))}."
        )
    if snapshot.get("schema_version") != manifest.get("version"):
        issues.append("Source snapshot schema_version does not match source-manifest version.")
    try:
        generated_at = parse_iso_timestamp(snapshot.get("generated_at"))
    except ValueError as error:
        issues.append(f"Source snapshot generated_at is invalid: {error}")
        generated_at = None
    if generated_at:
        report["generated_at"] = snapshot["generated_at"]
        if generated_at > now + timedelta(minutes=5):
            issues.append("Source snapshot generated_at is in the future.")
        if now - generated_at > timedelta(days=max_age_days):
            issues.append(f"Source snapshot is older than the {max_age_days}-day release-review window.")

    allowed_statuses = {"not_requested", "success", "partial", "failed", "unavailable", "blocked"}
    for source_id, manifest_source in manifest_by_id.items():
        if not isinstance(manifest_source, dict) or not source_id:
            continue
        attribution = manifest_source.get("attribution")
        if not isinstance(attribution, str) or not attribution.strip():
            issues.append(f"Source {source_id or '<missing>'} has no attribution.")
        snapshot_source = snapshot_by_id.get(source_id)
        if not isinstance(snapshot_source, dict):
            continue
        for field in SOURCE_FIELDS:
            if snapshot_source.get(field) != manifest_source.get(field):
                issues.append(f"Source snapshot drift for {source_id}.{field}.")
        status = snapshot_source.get("status")
        report["statuses"][source_id] = status
        if status not in allowed_statuses:
            issues.append(f"Source {source_id} has unsupported snapshot status {status!r}.")
        for count_name in ("accepted", "rejected"):
            count = snapshot_source.get(count_name)
            if not isinstance(count, int) or isinstance(count, bool) or count < 0:
                issues.append(f"Source {source_id}.{count_name} must be a non-negative integer.")
    report["source_count"] = len(manifest_sources)
    return report, issues


def advisory_audit(root: Path, dependency_report: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    path = root / "scripts/release_advisories.json"
    if not path.is_file():
        return [], ["scripts/release_advisories.json is missing."]
    try:
        document = json.loads(read_text(path))
    except (OSError, json.JSONDecodeError) as error:
        return [], [f"Advisory manifest could not be parsed: {error}"]
    if document.get("schema_version") != 1 or not isinstance(document.get("advisories"), list):
        return [], ["Advisory manifest must use schema_version 1 and contain advisories."]

    issues: list[str] = []
    report: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    versions = dependency_report.get("versions", {})
    wrapper_version = dependency_report.get("wrapper")
    for advisory in document["advisories"]:
        if not isinstance(advisory, dict):
            issues.append("Advisory manifest contains a non-object entry.")
            continue
        required = ("id", "component", "dependency_key", "fixed_in", "disposition", "source", "rationale")
        missing = [field for field in required if not advisory.get(field)]
        if missing:
            issues.append(f"Advisory entry is missing: {', '.join(missing)}.")
            continue
        advisory_id = str(advisory["id"])
        if advisory_id in seen_ids:
            issues.append(f"Advisory manifest repeats {advisory_id}.")
        seen_ids.add(advisory_id)
        disposition = str(advisory["disposition"])
        if disposition not in ADVISORY_DISPOSITIONS:
            issues.append(f"Advisory {advisory_id} has unsupported disposition {disposition}.")
        current = wrapper_version if advisory["dependency_key"] == "gradle_wrapper" else versions.get(advisory["dependency_key"])
        if not current:
            issues.append(f"Advisory {advisory_id} references an unknown dependency key {advisory['dependency_key']}.")
            continue
        try:
            is_fixed = version_key(str(current)) >= version_key(str(advisory["fixed_in"]))
        except ValueError as error:
            issues.append(f"Advisory {advisory_id} has an invalid version: {error}.")
            continue
        evidence_files = advisory.get("evidence_files", [])
        if not isinstance(evidence_files, list) or not evidence_files:
            issues.append(f"Advisory {advisory_id} needs at least one evidence file.")
        else:
            for evidence_file in evidence_files:
                if not (root / str(evidence_file)).is_file():
                    issues.append(f"Advisory {advisory_id} evidence file is missing: {evidence_file}.")
        if is_fixed and disposition != "resolved":
            issues.append(f"Advisory {advisory_id} is fixed in {current} but is not marked resolved.")
        if not is_fixed and disposition == "resolved":
            issues.append(f"Advisory {advisory_id} is below fixed version {advisory['fixed_in']} but is marked resolved.")
        report.append(
            {
                "id": advisory_id,
                "component": advisory["component"],
                "current": current,
                "fixed_in": advisory["fixed_in"],
                "disposition": disposition,
                "state": "resolved" if is_fixed else "documented-risk",
                "source": advisory["source"],
            }
        )
    return report, issues


def release_metadata_audit(root: Path, version_name: str, version_code: int) -> list[str]:
    issues: list[str] = []
    required_files = {
        "README.md": root / "README.md",
        "CHANGELOG.md": root / "CHANGELOG.md",
        "ChangelogScreen.kt": root / "app/src/main/java/com/sysadmindoc/callshield/ui/screens/more/ChangelogScreen.kt",
        "F-Droid metadata": root / "docs/fdroid/com.sysadmindoc.callshield.yml",
        "F-Droid runbook": root / "docs/fdroid-submission.md",
        "Fastlane changelog": root / f"fastlane/metadata/android/en-US/changelogs/{version_code}.txt",
    }
    missing = [label for label, path in required_files.items() if not path.is_file()]
    if missing:
        return [f"Missing release metadata: {', '.join(missing)}."]
    readme = read_text(required_files["README.md"])
    changelog = read_text(required_files["CHANGELOG.md"])
    screen = read_text(required_files["ChangelogScreen.kt"])
    fdroid = read_text(required_files["F-Droid metadata"])
    runbook = read_text(required_files["F-Droid runbook"])
    store_changelog = read_text(required_files["Fastlane changelog"]).strip()
    if f"## v{version_name} Highlights" not in readme:
        issues.append(f"README has no current-release highlights for v{version_name}.")
    if f"## Detection Pipeline (v{version_name})" not in readme:
        issues.append(f"README detection-pipeline heading is not v{version_name}.")
    if "img.shields.io/github/v/release/SysAdminDoc/CallShield" not in readme:
        issues.append("README release badge is missing.")
    if changelog.count("## Unreleased") != 1 or f"## v{version_name}" not in changelog:
        issues.append(f"CHANGELOG must contain one Unreleased section and a v{version_name} section.")
    if not re.search(rf'VersionEntry\(\s*"{re.escape(version_name)}"', screen):
        issues.append(f"In-app ChangelogScreen does not lead with v{version_name}.")
    if "isLatest = true" not in screen:
        issues.append("In-app ChangelogScreen has no latest marker.")
    if not store_changelog or len(store_changelog) > 500:
        issues.append(f"Fastlane changelog {version_code}.txt is missing, empty, or over 500 characters.")

    # The advertised database size moves on every community merge, and it is
    # quoted in six places. The 2026-09-05 drain took it from 51,502 to 51,634
    # and nothing caught the drift.
    database_file = root / "data/spam_numbers.json"
    if not database_file.is_file():
        issues.append("Spam database is missing.")
    else:
        try:
            database = json.loads(read_text(database_file))
            row_count = len(database["numbers"])
        except (ValueError, KeyError, TypeError):
            issues.append("Spam database does not parse or has no numbers array.")
        else:
            grouped = f"{row_count:,}"
            encoded = grouped.replace(",", "%2C")
            stale = [
                token
                for token in re.findall(r"\d{1,3}(?:,\d{3})+", readme)
                if token != grouped and f"{token} spam numbers" in readme
            ]
            if grouped not in readme or encoded not in readme or stale:
                issues.append(
                    f"README database size is stale; data/spam_numbers.json holds {grouped} numbers."
                )

    # The string and plural counts are a translation-readiness claim, and they
    # drift every time a feature adds resources. Nothing gated them, so the
    # README sat at 1160/29 while the resource file held 1404/33.
    strings_file = root / "app/src/main/res/values/strings.xml"
    if not strings_file.is_file():
        issues.append("Base string resources are missing.")
    else:
        resources = read_text(strings_file)
        string_count = len(re.findall(r"<string\s", resources))
        plural_count = len(re.findall(r"<plurals\s", resources))
        claim = f"| Strings | {string_count} string resources and {plural_count} plural groups"
        if claim not in readme:
            issues.append(
                f"README declares the wrong resource counts; base resources hold "
                f"{string_count} strings and {plural_count} plural groups."
            )

    source_marker = f"Current app source: {version_name} ({version_code});"
    if source_marker not in fdroid:
        issues.append("F-Droid metadata does not identify the current app source version/code.")
    prepared_version = re.search(r"(?m)^CurrentVersion:\s*(\S+)\s*$", fdroid)
    prepared_code = re.search(r"(?m)^CurrentVersionCode:\s*(\d+)\s*$", fdroid)
    build_versions = re.findall(r"(?m)^\s*-\s+versionName:\s*(\S+)\s*$", fdroid)
    build_codes = re.findall(r"(?m)^\s+versionCode:\s*(\d+)\s*$", fdroid)
    if not prepared_version or not prepared_code or not build_versions or not build_codes:
        issues.append("F-Droid metadata must contain a prepared build and CurrentVersion fields.")
    else:
        if prepared_version.group(1) != build_versions[-1] or prepared_code.group(1) != build_codes[-1]:
            issues.append("F-Droid CurrentVersion fields do not match the last prepared build.")
        if f"Latest release prepared for verification: `v{prepared_version.group(1)}`" not in runbook:
            issues.append("F-Droid runbook release version differs from metadata.")
        if f"Version code: `{prepared_code.group(1)}`" not in runbook:
            issues.append("F-Droid runbook version code differs from metadata.")
    return issues


def audit(
    root: Path = ROOT,
    *,
    now: datetime | None = None,
    max_snapshot_age_days: int = MAX_SNAPSHOT_AGE_DAYS,
) -> dict[str, Any]:
    current_time = now or datetime.now(timezone.utc)
    issues: list[str] = []
    app_path = root / "app/build.gradle.kts"
    try:
        version_name, version_code = parse_app_version(read_text(app_path))
    except (OSError, ValueError) as error:
        version_name, version_code = "unknown", -1
        issues.append(f"Application release version could not be parsed: {error}")
    dependency_report, dependency_issues = dependency_audit(root)
    source_report, source_issues = source_audit(root, current_time, max_snapshot_age_days)
    advisories, advisory_issues = advisory_audit(root, dependency_report)
    issues.extend(release_metadata_audit(root, version_name, version_code))
    issues.extend(dependency_issues)
    issues.extend(source_issues)
    issues.extend(advisory_issues)
    return {
        "version_name": version_name,
        "version_code": version_code,
        "dependencies": dependency_report,
        "sources": source_report,
        "advisories": advisories,
        "issues": issues,
    }


def print_report(report: dict[str, Any]) -> None:
    print(f"Release: v{report['version_name']} (versionCode {report['version_code']})")
    dependencies = report["dependencies"]
    print(f"Gradle wrapper: {dependencies.get('wrapper') or 'missing'}")
    print(f"Dependency catalog: {len(dependencies.get('versions', {}))} versions; "
          f"{dependencies.get('locked_direct_libraries', 0)} direct libraries checked against "
          f"{dependencies.get('lock_entries', 0)} lock entries")
    sources = report["sources"]
    print(f"Source snapshot: {sources.get('generated_at') or 'missing'}; "
          f"{sources.get('source_count', 0)} manifest sources; statuses={sources.get('statuses', {})}")
    for advisory in report["advisories"]:
        print(
            f"Advisory: {advisory['id']} {advisory['component']} "
            f"{advisory['current']} (fixed in {advisory['fixed_in']}) "
            f"[{advisory['state']}; {advisory['disposition']}] {advisory['source']}"
        )
    if report["issues"]:
        print("FAIL: release drift or incomplete provenance detected")
        for issue in report["issues"]:
            print(f"- {issue}")
    else:
        print("PASS: release metadata, dependency lock, advisory dispositions, and source provenance are synchronized")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root (defaults to this checkout)")
    parser.add_argument(
        "--max-snapshot-age-days",
        type=int,
        default=MAX_SNAPSHOT_AGE_DAYS,
        help=f"maximum source snapshot age (default: {MAX_SNAPSHOT_AGE_DAYS})",
    )
    parser.add_argument("--json", action="store_true", dest="as_json", help="emit the report as JSON")
    args = parser.parse_args(argv)
    report = audit(args.root.resolve(), max_snapshot_age_days=args.max_snapshot_age_days)
    if args.as_json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print_report(report)
    return 1 if report["issues"] else 0


if __name__ == "__main__":
    sys.exit(main())
