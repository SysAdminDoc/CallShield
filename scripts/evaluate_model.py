#!/usr/bin/env python3
"""
CallShield On-Device Spam Scorer — Model Evaluator

Reports precision / recall / F1 / accuracy for `data/spam_model_weights.json`
as part of local verification (roadmap 2.6.3). Two views are produced:

  1. On-device metrics — scores the SHIPPED weights with the exact inference the
     Android app runs (`SpamMLScorer.scoreGbt`: sigmoid over Σ leaf·learning_rate,
     NO sklearn `init_` term), at the model's own decision threshold. This is the
     honest picture of what users actually get, and it catches export/inference
     drift that the trainer's sklearn-side test metrics would hide.
  2. Cross-validated metrics — stratified k-fold on the same GBT config, giving
     an unbiased generalization estimate (the shipped weights were trained on a
     subset of this same data, so their on-device metrics are optimistic).

Exits non-zero when the cross-validated F1 falls below `--min-f1`, so it can act
as a local quality gate before shipping a retrained model.

Usage:
    python evaluate_model.py
    python evaluate_model.py --model data/spam_model_weights.json --min-f1 0.90
    python evaluate_model.py --folds 5
"""

import argparse
import json
import math
from pathlib import Path

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import StratifiedKFold

from train_spam_model import build_dataset, FEATURE_NAMES, OUTPUT_FILE


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


def score_gbt(features: list[float], trees: list[dict], learning_rate: float) -> float:
    """On-device GBT score — matches SpamMLScorer.scoreGbt exactly."""
    raw = 0.0
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
    parser.add_argument("--min-f1", type=float, default=0.60,
                        help="Fail (exit 1) if cross-validated F1 is below this "
                             "regression floor (current baseline is ~0.66 on the "
                             "synthetic negative set)")
    args = parser.parse_args()

    model_path = Path(args.model)
    if not model_path.exists():
        print(f"ERROR: model not found: {model_path}. Run train_spam_model.py first.")
        return 1

    with open(model_path) as f:
        model = json.load(f)

    threshold = float(model.get("threshold", 0.7))
    learning_rate = float(model.get("learning_rate", 0.1))
    trees = model.get("trees", [])
    fallback_weights = model.get("fallback_weights", {})
    fallback_bias = float(model.get("fallback_bias", 0.0))

    print("=== CallShield Spam Model Evaluation ===\n")
    print(f"Model: {model_path}")
    print(f"  version={model.get('version')}  type={model.get('model_type')}  "
          f"trees={len(trees)}  threshold={threshold}  learning_rate={learning_rate}\n")

    print("Building labeled dataset (spam positives + synthetic legit negatives)...")
    X, y, spam_numbers, negative_numbers = build_dataset()
    print(f"  positives={len(spam_numbers[:50000]):,}  negatives={len(negative_numbers):,}  "
          f"total={len(X):,}\n")

    # ── 1. On-device inference metrics (shipped weights, full dataset) ──
    if trees:
        preds = [1 if score_gbt(f, trees, learning_rate) >= threshold else 0 for f in X]
        print_metrics(f"[on-device GBT @ threshold {threshold}] (shipped weights, full set — optimistic)",
                      metrics(y, preds))
    else:
        print("No GBT trees in model; skipping GBT on-device evaluation.")

    # On-device both GBT and LR are thresholded at the model's `threshold`
    # (SpamMLScorer: isSpam = score >= threshold), not 0.5.
    lr_preds = [1 if score_lr(f, fallback_weights, fallback_bias) >= threshold else 0 for f in X]
    print_metrics(f"[on-device LR fallback @ threshold {threshold}] (full set)", metrics(y, lr_preds))
    print()

    # ── 2. Cross-validated GBT metrics (honest generalization estimate) ──
    print(f"Cross-validating GBT config ({args.folds}-fold stratified)...")
    X_np = np.array(X)
    y_np = np.array(y)
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
    print(f"\n[cross-validated GBT] mean precision={mean_prec:.4f}  "
          f"recall={mean_rec:.4f}  F1={mean_f1:.4f}\n")

    if mean_f1 < args.min_f1:
        print(f"FAIL: cross-validated F1 {mean_f1:.4f} < required {args.min_f1:.4f}")
        return 1

    print(f"OK: cross-validated F1 {mean_f1:.4f} >= required {args.min_f1:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
