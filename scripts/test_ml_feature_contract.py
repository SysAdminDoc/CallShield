#!/usr/bin/env python3
"""Cross-language golden-vector and shipped-model schema checks."""

import json
from pathlib import Path

from evaluate_model import score_gbt, score_lr
from train_spam_model import FEATURE_NAMES, FEATURE_SCHEMA_VERSION, extract_features


ROOT = Path(__file__).resolve().parents[1]
EPSILON = 1e-12


def main() -> None:
    fixture = json.loads((Path(__file__).parent / "ml_feature_fixtures.json").read_text(encoding="utf-8"))
    assert fixture["schema_version"] == FEATURE_SCHEMA_VERSION
    assert fixture["feature_names"] == FEATURE_NAMES

    model = json.loads((ROOT / "data" / "spam_model_weights.json").read_text(encoding="utf-8"))
    assert model["feature_schema_version"] == FEATURE_SCHEMA_VERSION
    assert model["feature_names"] == FEATURE_NAMES

    for case in fixture["cases"]:
        actual = extract_features(case["input"], case["hour"])
        expected = case["expected"]
        assert len(actual) == len(FEATURE_NAMES), case["name"]
        for index, (actual_value, expected_value) in enumerate(zip(actual, expected)):
            assert abs(actual_value - expected_value) <= EPSILON, (
                f"{case['name']} feature {FEATURE_NAMES[index]}: "
                f"expected {expected_value}, got {actual_value}"
            )

        if case["scoreable"]:
            gbt = score_gbt(actual, model["trees"], model["learning_rate"], model["initial_score"])
            lr = score_lr(actual, model["fallback_weights"], model["fallback_bias"])
            assert abs(gbt - case["gbt_score"]) <= EPSILON, f"{case['name']} GBT score drift"
            assert abs(lr - case["lr_score"]) <= EPSILON, f"{case['name']} LR score drift"

    print(f"ML feature contract passed: {len(fixture['cases'])} cases, {len(FEATURE_NAMES)} features")


if __name__ == "__main__":
    main()
