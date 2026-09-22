#!/usr/bin/env python3
"""
CallShield On-Device Spam Scorer — Model Evaluator

Reports precision / recall / F1 / accuracy for `data/spam_model_weights.json`
as part of local verification (roadmap 2.6.3). Three views are produced:

  1. On-device held-out metrics — scores the SHIPPED weights with the exact
     inference the Android app runs (`SpamMLScorer.scoreGbt`: sigmoid over
     initial_score plus Σ leaf·learning_rate), at the model's own decision
     threshold, on a 20% evaluation split that was NOT used for training or
     threshold calibration. This is the gate metric.
  2. On-device full-set metrics — the same inference on every sample. Because
     the shipped weights were trained on a subset of this data, the full-set
     figures are in-sample and therefore optimistic.
  3. Cross-validated metrics — stratified k-fold on the same GBT config, giving
     an unbiased generalization estimate at sklearn's 0.5 threshold (not the
     shipped threshold).

Exits non-zero when the on-device held-out F1 falls below `--min-f1`.

Usage:
    python evaluate_model.py
    python evaluate_model.py --model data/spam_model_weights.json --min-f1 0.45
    python evaluate_model.py --folds 5
"""

import argparse
import json
import math
from pathlib import Path

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import StratifiedKFold

from train_spam_model import (
    FEATURE_NAMES,
    FEATURE_SCHEMA_VERSION,
    OUTPUT_FILE,
    build_dataset,
)


def sigmoid(x: float) -> float:
    if x < -60:
        return 0.0
    if x > 60:
        return 1.0
    return 1.0 / (1.0 + math.exp(-x))


def evaluate_tree(features: list[float], tree: dict) -> float:
    """Traverse one exported regression tree; return the reached leaf value.
    Mirrors SpamMLScorer.evaluateTree (feature == -2 marks a leaf)."""
    feat = tree["feature"]
    thr = tree["threshold"]
    left = tree["children_left"]
    right = tree["children_right"]
    val = tree["value"]
    node = 0
    # Bounded by tree depth; guard against a malformed cyclic tree.
    for _ in range(len(feat) + 1):
        if feat[node] == -2:      # leaf
            return float(val[node])
        if features[feat[node]] <= thr[node]:
            node = left[node]
        else:
            node = right[node]
    return float(val[node])


def score_gbt(
    features: list[float],
    trees: list[dict],
    learning_rate: float,
    initial_score: float = 0.0,
) -> float:
    """On-device GBT score — matches SpamMLScorer.scoreGbt exactly."""
    raw = initial_score
    for tree in trees:
        raw += evaluate_tree(features, tree) * learning_rate
    return sigmoid(raw)


def score_lr(features: list[float], weights: dict, bias: float) -> float:
    """Logistic-regression fallback score — matches the Kotlin LR path."""
    z = bias + sum(weights.get(name, 0.0) * f for name, f in zip(FEATURE_NAMES, features))
    return sigmoid(z)


def metrics(y_true: list[int], y_pred: list[int]) -> dict:
    tp = tn = fp = fn = 0
    for yt, yp in zip(y_true, y_pred):
        if yp == 1 and yt == 1:
            tp += 1
        elif yp == 0 and yt == 0:
            tn += 1
        elif yp == 1 and yt == 0:
            fp += 1
        else:
            fn += 1
    prec = tp / max(1, tp + fp)
    rec = tp / max(1, tp + fn)
    f1 = 2 * prec * rec / max(1e-9, prec + rec)
    acc = (tp + tn) / max(1, tp + tn + fp + fn)
    return {"precision": prec, "recall": rec, "f1": f1, "accuracy": acc,
            "tp": tp, "fp": fp, "tn": tn, "fn": fn}


def print_metrics(label: str, m: dict) -> None:
    print(f"{label}")
    print(f"  precision={m['precision']:.4f}  recall={m['recall']:.4f}  "
          f"F1={m['f1']:.4f}  accuracy={m['accuracy']:.4f}")
    print(f"  TP={m['tp']:,}  FP={m['fp']:,}  TN={m['tn']:,}  FN={m['fn']:,}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate the CallShield spam model.")
    parser.add_argument("--model", default=str(OUTPUT_FILE),
                        help="Path to spam_model_weights.json")
    parser.add_argument("--folds", type=int, default=5,
                        help="Stratified k-fold count for the CV estimate")
    parser.add_argument("--min-f1", type=float, default=0.45,
                        help="Fail (exit 1) if the shipped weights' held-out F1 "
                             "at the shipped threshold is below this floor")
    args = parser.parse_args()

    model_path = Path(args.model)
    if not model_path.exists():
        print(f"ERROR: model not found: {model_path}. Run train_spam_model.py first.")
        return 1

    with open(model_path) as f:
        model = json.load(f)

    if (
        model.get("feature_schema_version") != FEATURE_SCHEMA_VERSION
        or model.get("feature_names") != FEATURE_NAMES
    ):
        print("ERROR: model feature schema does not match the trainer contract.")
        return 1

    threshold = float(model.get("threshold", 0.7))
    learning_rate = float(model.get("learning_rate", 0.1))
    initial_score = float(model.get("initial_score", 0.0))
    trees = model.get("trees", [])
    fallback_weights = model.get("fallback_weights", {})
    fallback_bias = float(model.get("fallback_bias", 0.0))

    print("=== CallShield Spam Model Evaluation ===\n")
    print(f"Model: {model_path}")
    print(f"  version={model.get('version')}  type={model.get('model_type')}  "
          f"trees={len(trees)}  threshold={threshold}  learning_rate={learning_rate}  "
          f"initial_score={initial_score:+.6f}\n")

    print("Building labeled dataset (spam positives + synthetic legit negatives)...")
    X, y, spam_numbers, negative_numbers = build_dataset()
    print(f"  positives={len(spam_numbers[:50000]):,}  negatives={len(negative_numbers):,}  "
          f"total={len(X):,}\n")

    # Reproduce the trainer's deterministic 60/20/20 split so we can identify
    # the evaluation slice that was never used for fitting or calibration.
    # build_dataset calls random.seed(42) internally before generating negatives,
    # so the RNG state after it returns is deterministic. The trainer shuffles
    # with whatever state remains (no re-seed), so we must do the same.
    import random as _rand
    combined = list(zip(X, y))
    _rand.shuffle(combined)
    X_all = [c[0] for c in combined]
    y_all = [c[1] for c in combined]
    split_train = int(len(X_all) * 0.6)
    split_cal = int(len(X_all) * 0.8)
    X_eval = X_all[split_cal:]
    y_eval = y_all[split_cal:]

    # ── 1. On-device inference on the held-out evaluation split (GATE) ──
    gate_f1 = 0.0
    if trees:
        eval_preds = [
            1 if score_gbt(f, trees, learning_rate, initial_score) >= threshold else 0
            for f in X_eval
        ]
        eval_m = metrics(y_eval, eval_preds)
        print_metrics(f"[on-device GBT @ threshold {threshold}] (held-out evaluation split)",
                      eval_m)
        gate_f1 = eval_m["f1"]
    else:
        print("No GBT trees in model; skipping GBT on-device evaluation.")

    eval_lr_preds = [1 if score_lr(f, fallback_weights, fallback_bias) >= threshold else 0 for f in X_eval]
    print_metrics(f"[on-device LR fallback @ threshold {threshold}] (held-out evaluation split)",
                  metrics(y_eval, eval_lr_preds))

    # ── 2. On-device inference on full set (in-sample, informational) ──
    if trees:
        full_preds = [
            1 if score_gbt(f, trees, learning_rate, initial_score) >= threshold else 0
            for f in X_all
        ]
        print_metrics(f"\n[on-device GBT @ threshold {threshold}] (full set — in-sample, informational)",
                      metrics(y_all, full_preds))
    print()

    if gate_f1 < args.min_f1:
        print(f"FAIL: held-out on-device F1 {gate_f1:.4f} < required {args.min_f1:.4f}")
        return 1

    print(f"OK: held-out on-device F1 {gate_f1:.4f} >= required {args.min_f1:.4f}")

    # ── 3. Cross-validated GBT metrics (generalization of config, informational) ──
    print(f"\nCross-validating GBT config ({args.folds}-fold stratified, informational)...")
    X_np = np.array(X_all)
    y_np = np.array(y_all)
    skf = StratifiedKFold(n_splits=args.folds, shuffle=True, random_state=42)
    fold_f1, fold_prec, fold_rec = [], [], []
    for fold, (tr, te) in enumerate(skf.split(X_np, y_np), 1):
        clf = GradientBoostingClassifier(
            n_estimators=int(model.get("n_estimators", 50)),
            max_depth=4, learning_rate=learning_rate,
            min_samples_leaf=10, random_state=42,
        )
        clf.fit(X_np[tr], y_np[tr])
        pred = clf.predict(X_np[te])
        m = metrics(y_np[te].tolist(), pred.tolist())
        fold_f1.append(m["f1"]); fold_prec.append(m["precision"]); fold_rec.append(m["recall"])
        print(f"  fold {fold}: prec={m['precision']:.4f} rec={m['recall']:.4f} F1={m['f1']:.4f}")

    mean_f1 = sum(fold_f1) / len(fold_f1)
    mean_prec = sum(fold_prec) / len(fold_prec)
    mean_rec = sum(fold_rec) / len(fold_rec)
    print(f"\n[cross-validated GBT @ sklearn 0.5] mean precision={mean_prec:.4f}  "
          f"recall={mean_rec:.4f}  F1={mean_f1:.4f}")

    # ── 4. Inference-hour invariance ────────────────────────────────────
    # build_dataset pins the time features to a single reference hour, but the
    # app feeds the real device hour (SpamMLScorer: Calendar HOUR_OF_DAY). If
    # the trees ever split on time_of_day_*, a caller's verdict changes with
    # the clock. Sweep all 24 hours over the shipped weights and require the
    # score to be constant.
    sin_idx = FEATURE_NAMES.index("time_of_day_sin")
    cos_idx = FEATURE_NAMES.index("time_of_day_cos")

    def hour_spread(scorer) -> tuple[float, int]:
        worst, worst_idx = 0.0, 0
        for i, features in enumerate(X_all[:200]):
            scores = []
            for hour in range(24):
                angle = 2.0 * math.pi * hour / 24.0
                probe = list(features)
                probe[sin_idx] = math.sin(angle)
                probe[cos_idx] = math.cos(angle)
                scores.append(scorer(probe))
            spread = max(scores) - min(scores)
            if spread > worst:
                worst, worst_idx = spread, i
        return worst, worst_idx

    checks = [("LR fallback", lambda f: score_lr(f, fallback_weights, fallback_bias))]
    if trees:
        checks.insert(
            0,
            ("GBT", lambda f: score_gbt(f, trees, learning_rate, initial_score)),
        )

    for label, scorer in checks:
        spread, idx = hour_spread(scorer)
        if spread > 1e-9:
            print(
                f"\nFAIL: {label} score varies by {spread:.4f} across inference hours "
                f"(worst sample index: {idx}). The model learned a time-of-day dependence "
                f"from fixed-hour training data; drop unscoreable rows and retrain."
            )
            return 1
        print(f"OK: {label} score is invariant across all 24 inference hours")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
