#!/usr/bin/env python3
"""Generate and verify a CycloneDX SBOM plus an in-toto provenance record.

The release APK is the subject. Runtime Maven components come from the
checked-in releaseRuntimeClasspath lock entries, so a changed lockfile or
artifact cannot be silently represented by an older SBOM.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import uuid
from pathlib import Path
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "app" / "gradle.lockfile"
WRAPPER_PATH = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
REPOSITORY_URI = "https://github.com/SysAdminDoc/CallShield.git"
RUNTIME_CONFIGURATION = "releaseRuntimeClasspath"
SCHEMA_VERSION = 1


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def parse_app_metadata(build_path: Path = ROOT / "app" / "build.gradle.kts") -> tuple[str, int]:
    text = build_path.read_text(encoding="utf-8")
    version_match = re.search(r"(?m)^\s*versionName\s*=\s*\"([^\"]+)\"\s*$", text)
    code_match = re.search(r"(?m)^\s*versionCode\s*=\s*(\d+)\s*$", text)
    if not version_match or not code_match:
        raise ValueError("app/build.gradle.kts must declare versionName and versionCode")
    return version_match.group(1), int(code_match.group(1))


def parse_runtime_lock(lock_path: Path = LOCK_PATH) -> list[tuple[str, str, str]]:
    components: set[tuple[str, str, str]] = set()
    for line_number, line in enumerate(lock_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line or line.startswith("#") or "=" not in line:
            continue
        coordinate, configurations = line.split("=", 1)
        if RUNTIME_CONFIGURATION not in configurations.split(","):
            continue
        parts = coordinate.split(":")
        if len(parts) != 3 or not all(parts):
            raise ValueError(f"Unsupported lock coordinate at {lock_path}:{line_number}: {coordinate}")
        components.add((parts[0], parts[1], parts[2]))
    if not components:
        raise ValueError(f"No {RUNTIME_CONFIGURATION} components found in {lock_path}")
    return sorted(components)


def maven_purl(group: str, name: str, version: str) -> str:
    return f"pkg:maven/{quote(group, safe='.-_')}/{quote(name, safe='.-_')}@{quote(version, safe='.-_+')}"


def app_ref(version: str) -> str:
    return f"pkg:generic/callshield@{quote(version, safe='.-_+')}"


def git_revision() -> str:
    configured = os.environ.get("GITHUB_SHA")
    if configured:
        return configured
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def json_bytes(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n").encode("utf-8")


def build_sbom(
    artifact: Path,
    artifact_sha256: str,
    lock_sha256: str,
    version: str,
    version_code: int,
    components: list[tuple[str, str, str]],
) -> dict:
    refs = sorted(maven_purl(*component) for component in components)
    application_ref = app_ref(version)
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:" + str(uuid.UUID(hex=hashlib.sha256((artifact_sha256 + lock_sha256).encode()).hexdigest()[:32])),
        "version": 1,
        "metadata": {
            "tools": [{"vendor": "SysAdminDoc", "name": "release_sbom.py", "version": str(SCHEMA_VERSION)}],
            "component": {
                "type": "application",
                "bom-ref": application_ref,
                "group": "SysAdminDoc",
                "name": "CallShield",
                "version": version,
                "purl": application_ref,
            },
            "properties": [
                {"name": "callshield:version-code", "value": str(version_code)},
                {"name": "callshield:artifact-name", "value": artifact.name},
                {"name": "callshield:artifact-sha256", "value": artifact_sha256},
                {"name": "callshield:dependency-lock-sha256", "value": lock_sha256},
            ],
        },
        "components": [
            {
                "type": "library",
                "bom-ref": maven_purl(group, name, component_version),
                "group": group,
                "name": name,
                "version": component_version,
                "purl": maven_purl(group, name, component_version),
                "scope": "required",
            }
            for group, name, component_version in components
        ],
        "dependencies": [{"ref": application_ref, "dependsOn": refs}],
    }


def build_provenance(
    artifact: Path,
    artifact_sha256: str,
    sbom_sha256: str,
    lock_sha256: str,
    version: str,
    version_code: int,
) -> dict:
    revision = git_revision()
    materials = [
        {"uri": "file:app/gradle.lockfile", "digest": {"sha256": lock_sha256}},
        {"uri": "file:gradle/wrapper/gradle-wrapper.properties", "digest": {"sha256": sha256_file(WRAPPER_PATH)}},
    ]
    if re.fullmatch(r"[0-9a-f]{40}", revision):
        materials.insert(0, {"uri": "git+" + REPOSITORY_URI, "digest": {"sha1": revision}})

    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    repository = os.environ.get("GITHUB_REPOSITORY", "SysAdminDoc/CallShield")
    run_id = os.environ.get("GITHUB_RUN_ID", "local")
    return {
        "_type": "https://in-toto.io/Statement/v1",
        "subject": [{"name": artifact.name, "digest": {"sha256": artifact_sha256}}],
        "predicateType": "https://slsa.dev/provenance/v1",
        "predicate": {
            "buildDefinition": {
                "buildType": f"{server}/{repository}/.github/workflows/verification.yml@release-artifact",
                "externalParameters": {
                    "artifact": artifact.name,
                    "version": version,
                    "version_code": version_code,
                },
                "internalParameters": {
                    "sbom_sha256": sbom_sha256,
                    "dependency_lock_sha256": lock_sha256,
                },
                "resolvedDependencies": materials,
            },
            "runDetails": {
                "builder": {"id": f"{server}/{repository}/actions/runner"},
                "metadata": {"invocationId": run_id, "sourceRevision": revision},
            },
        },
    }


def output_paths(artifact: Path, output_dir: Path) -> tuple[Path, Path, Path]:
    prefix = artifact.stem
    return (
        output_dir / f"{prefix}.cdx.json",
        output_dir / f"{prefix}.provenance.json",
        output_dir / f"{prefix}.sha256",
    )


def generate_bundle(artifact: Path, output_dir: Path) -> tuple[Path, Path, Path]:
    if not artifact.is_file():
        raise ValueError(f"Release artifact does not exist: {artifact}")
    output_dir.mkdir(parents=True, exist_ok=True)
    version, version_code = parse_app_metadata()
    lock_sha256 = sha256_file(LOCK_PATH)
    artifact_sha256 = sha256_file(artifact)
    components = parse_runtime_lock()
    sbom = build_sbom(artifact, artifact_sha256, lock_sha256, version, version_code, components)
    sbom_path, provenance_path, hash_path = output_paths(artifact, output_dir)
    sbom_path.write_bytes(json_bytes(sbom))
    sbom_sha256 = sha256_file(sbom_path)
    provenance = build_provenance(artifact, artifact_sha256, sbom_sha256, lock_sha256, version, version_code)
    provenance_path.write_bytes(json_bytes(provenance))
    hash_path.write_text(f"{artifact_sha256}  {artifact.name}", encoding="ascii", newline="")
    verify_bundle(artifact, output_dir)
    return sbom_path, provenance_path, hash_path


def verify_bundle(artifact: Path, output_dir: Path) -> None:
    sbom_path, provenance_path, hash_path = output_paths(artifact, output_dir)
    for path in (sbom_path, provenance_path, hash_path):
        if not path.is_file():
            raise ValueError(f"Missing release provenance output: {path}")

    version, version_code = parse_app_metadata()
    artifact_sha256 = sha256_file(artifact)
    lock_sha256 = sha256_file(LOCK_PATH)
    components = parse_runtime_lock()
    sbom = read_json(sbom_path)
    if sbom.get("bomFormat") != "CycloneDX" or sbom.get("specVersion") != "1.5":
        raise ValueError("SBOM must be CycloneDX 1.5")
    metadata = sbom.get("metadata")
    if not isinstance(metadata, dict):
        raise ValueError("SBOM metadata is missing")
    application_ref = app_ref(version)
    metadata_component = metadata.get("component")
    if not isinstance(metadata_component, dict) or any(
        metadata_component.get(key) != value
        for key, value in {
            "bom-ref": application_ref,
            "purl": application_ref,
            "version": version,
        }.items()
    ):
        raise ValueError("SBOM application metadata does not match the current release")
    properties = {item.get("name"): item.get("value") for item in metadata.get("properties", []) if isinstance(item, dict)}
    expected_properties = {
        "callshield:version-code": str(version_code),
        "callshield:artifact-name": artifact.name,
        "callshield:artifact-sha256": artifact_sha256,
        "callshield:dependency-lock-sha256": lock_sha256,
    }
    if any(properties.get(key) != value for key, value in expected_properties.items()):
        raise ValueError("SBOM metadata does not match the current release artifact or lockfile")
    expected_components = set(components)
    actual_components = {
        (component.get("group"), component.get("name"), component.get("version"))
        for component in sbom.get("components", [])
        if isinstance(component, dict)
    }
    if actual_components != expected_components:
        raise ValueError("SBOM components do not exactly match releaseRuntimeClasspath lock entries")
    dependencies = sbom.get("dependencies", [])
    if dependencies != [{"ref": application_ref, "dependsOn": sorted(maven_purl(*item) for item in components)}]:
        raise ValueError("SBOM dependency graph does not match its component set")

    provenance = read_json(provenance_path)
    subject = provenance.get("subject")
    if subject != [{"name": artifact.name, "digest": {"sha256": artifact_sha256}}]:
        raise ValueError("Provenance subject does not match the current artifact")
    predicate = provenance.get("predicate")
    if not isinstance(predicate, dict) or provenance.get("predicateType") != "https://slsa.dev/provenance/v1":
        raise ValueError("Provenance is not a SLSA v1 statement")
    internal = predicate.get("buildDefinition", {}).get("internalParameters", {})
    if internal.get("sbom_sha256") != sha256_file(sbom_path) or internal.get("dependency_lock_sha256") != lock_sha256:
        raise ValueError("Provenance does not bind the current SBOM and dependency lock")
    expected_sidecar = f"{artifact_sha256}  {artifact.name}"
    if hash_path.read_text(encoding="ascii") != expected_sidecar:
        raise ValueError("Artifact SHA-256 sidecar does not match the artifact")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("generate", "verify"))
    parser.add_argument("--artifact", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.command == "generate":
            paths = generate_bundle(args.artifact, args.output_dir)
            print("Generated: " + ", ".join(str(path) for path in paths))
        else:
            verify_bundle(args.artifact, args.output_dir)
            print("Release SBOM, provenance, and SHA-256 sidecar verified")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
