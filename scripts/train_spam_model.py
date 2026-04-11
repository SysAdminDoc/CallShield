#!/usr/bin/env python3
"""
CallShield On-Device Spam Scorer — Model Trainer (v3: Gradient-Boosted Trees)

Trains a gradient-boosted tree (GBT) ensemble on the CallShield spam database
and exports the model as a JSON tree ensemble (version 3). Also trains a
logistic regression fallback and embeds its weights for backward compatibility.

The Android app loads the JSON at startup and runs pure-Kotlin GBT inference
(or falls back to logistic regression on older model files).

Features (all derived locally, no network calls):
  1.  toll_free              — 800/888/877/etc. prefix
  2.  high_spam_npa          — area code in high FTC/FCC complaint set
  3.  voip_range             — NPA-NXX in known high-spam VoIP carrier range
  4.  repeated_digits_ratio  — fraction occupied by most-common digit
  5.  sequential_asc_ratio   — ascending sequential pairs / 9
  6.  all_same_digit         — all 10 digits identical
  7.  nxx_555                — exchange is 555 (unassigned test numbers)
  8.  last4_zero             — last 4 digits are 0000
  9.  invalid_nxx            — NXX starts with 0 or 1 (NANP-invalid, often spoofed)
  10. subscriber_all_same    — last 4 digits are all the same (9999, 1111, etc.)
  11. alternating_pattern    — even/odd digit positions are each uniform but differ
  12. sequential_desc_ratio  — descending sequential pairs / 9
  13. nxx_below_200          — NXX integer < 200 (often unassigned ranges)
  14. low_digit_entropy      — fewer than 4 distinct digits in full number
  15. subscriber_sequential  — last 4 form a complete ascending/descending run
  16. time_of_day_sin        — sin(2π * hour/24) cyclical time encoding
  17. time_of_day_cos        — cos(2π * hour/24) cyclical time encoding
  18. geographic_distance    — caller's area code differs from reference by >200
  19. short_number           — number has fewer than 7 digits (short codes)
  20. plus_one_prefix        — number starts with +1 (US/Canada)

Training data:
  Positive: all numbers in spam_numbers.json (spam)
  Negative: well-known US business NPA codes + random valid-format numbers

Usage:
    python train_spam_model.py
    python train_spam_model.py --output data/spam_model_weights.json
"""

import json
import math
import random
import argparse
from pathlib import Path
from collections import Counter

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.metrics import precision_score, recall_score, f1_score, roc_auc_score

DATA_DIR = Path(__file__).parent.parent / "data"
DB_FILE = DATA_DIR / "spam_numbers.json"
OUTPUT_FILE = DATA_DIR / "spam_model_weights.json"

HIGH_SPAM_VOIP_NPANXX = {
    "202555", "213226", "213555", "310555", "310400",
    "323555", "347555", "404555", "404430", "415555",
    "503555", "512555", "617555", "646555", "702555",
    "713555", "718555", "786555", "813555", "832555",
    "917555", "929555",
    "206455", "206456", "206457", "312454", "312455",
    "415523", "415524", "415525", "415526", "617286",
    "617453", "646397", "646398", "646399", "713291",
    "720420", "720421", "720660", "800289", "844258",
    "365234", "365235", "365236", "365237", "365238",
    "365239", "365240", "365241", "365242", "365243",
    "201984", "201985", "201986", "201987",
    "732412", "732413", "732414",
    "213260", "312320", "346570", "404400",
    "415320", "619320", "646320", "702320", "720320",
    "226506", "226507", "226508", "226509",
    "437370", "437371", "437372", "437373",
}

TOLL_FREE_PREFIXES = {"800", "888", "877", "866", "855", "844", "833"}

HIGH_SPAM_NPAS = {
    "800", "888", "877", "866", "855", "844", "833",
    "202", "213", "310", "323", "347", "404", "415",
    "512", "617", "646", "702", "713", "718", "786",
    "813", "832", "917", "929",
}

FEATURE_NAMES = [
    "toll_free",
    "high_spam_npa",
    "voip_range",
    "repeated_digits_ratio",
    "sequential_asc_ratio",
    "all_same_digit",
    "nxx_555",
    "last4_zero",
    "invalid_nxx",
    "subscriber_all_same",
    "alternating_pattern",
    "sequential_desc_ratio",
    "nxx_below_200",
    "low_digit_entropy",
    "subscriber_sequential",
    "time_of_day_sin",
    "time_of_day_cos",
    "geographic_distance",
    "short_number",
    "plus_one_prefix",
]

# Reference area code for geographic distance heuristic (Chicago, central US)
REFERENCE_NPA = 312


def extract_features(number: str) -> list[float]:
    """Extract 20-feature vector from a phone number string."""
    raw = number.replace("-", "").replace(" ", "").replace("(", "").replace(")", "")
    digits = raw.replace("+", "")
    raw_digit_len = len(digits)

    # Check raw number properties before normalizing
    plus_one_prefix = 1.0 if raw.startswith("+1") else 0.0
    short_number = 1.0 if raw_digit_len < 7 else 0.0

    if digits.startswith("1") and len(digits) == 11:
        digits = digits[1:]
    if len(digits) != 10:
        return [0.0] * len(FEATURE_NAMES)

    npa    = digits[:3]
    nxx    = digits[3:6]
    sub    = digits[6:]   # last 4
    npanxx = digits[:6]
    nxx_int = int(nxx) if nxx.isdigit() else 0

    # ── Features 1–8 (original) ──────────────────────────────────────
    f1_toll_free    = 1.0 if npa in TOLL_FREE_PREFIXES else 0.0
    f2_high_npa     = 1.0 if npa in HIGH_SPAM_NPAS else 0.0
    f3_voip         = 1.0 if npanxx in HIGH_SPAM_VOIP_NPANXX else 0.0

    counts = Counter(digits)
    f4_repeated     = counts.most_common(1)[0][1] / 10.0

    seq_asc  = sum(1 for i in range(9) if int(digits[i+1]) == int(digits[i]) + 1)
    f5_seq_asc = seq_asc / 9.0

    f6_all_same  = 1.0 if len(set(digits)) == 1 else 0.0
    f7_555       = 1.0 if nxx == "555" else 0.0
    f8_last4zero = 1.0 if sub == "0000" else 0.0

    # ── Features 9–15 (new) ──────────────────────────────────────────
    # NXX must start with 2–9 in NANP. Starting with 0 or 1 = invalid/spoofed.
    f9_invalid_nxx = 1.0 if nxx[0] in ("0", "1") else 0.0

    # Subscriber (last 4) all same digit: 9999, 0000, 1111, etc.
    f10_sub_all_same = 1.0 if len(set(sub)) == 1 else 0.0

    # Alternating: even-indexed and odd-indexed positions are each uniform
    # but different from each other (e.g. 5050505050, 1212121212)
    even_set = set(digits[i] for i in range(0, 10, 2))
    odd_set  = set(digits[i] for i in range(1, 10, 2))
    f11_alternating = 1.0 if (len(even_set) == 1 and len(odd_set) == 1
                               and even_set != odd_set) else 0.0

    seq_desc = sum(1 for i in range(9) if int(digits[i+1]) == int(digits[i]) - 1)
    f12_seq_desc = seq_desc / 9.0

    # NXX below 200 — many of these ranges are unassigned or rarely used legitimately
    f13_nxx_low = 1.0 if nxx_int < 200 else 0.0

    # Low entropy: fewer than 4 distinct digits in the full 10-digit number
    f14_low_entropy = 1.0 if len(counts) < 4 else 0.0

    # Subscriber is a fully sequential run: 1234, 2345, 9876, 8765, etc.
    sub_asc  = sum(1 for i in range(3) if int(sub[i+1]) == int(sub[i]) + 1)
    sub_desc = sum(1 for i in range(3) if int(sub[i+1]) == int(sub[i]) - 1)
    f15_sub_seq = 1.0 if (sub_asc == 3 or sub_desc == 3) else 0.0

    # ── Features 16–20 (behavioral & temporal) ─────────────────────
    # Cyclical time-of-day encoding — use fixed reference hour (noon) for training
    # since we don't have actual call timestamps. Model learns other features;
    # time features activate only at inference with real hour from device.
    rand_hour = 12
    time_angle = 2.0 * math.pi * rand_hour / 24.0
    f16_time_sin = math.sin(time_angle)
    f17_time_cos = math.cos(time_angle)

    # Geographic distance — caller's area code differs from reference by >200
    npa_int = int(npa) if npa.isdigit() else 0
    f18_geo_dist = 1.0 if abs(npa_int - REFERENCE_NPA) > 200 else 0.0

    # Short number — fewer than 7 digits (short codes often used for spam SMS)
    f19_short = short_number

    # Plus-one prefix — number starts with +1 (US/Canada)
    f20_plus_one = plus_one_prefix

    return [
        f1_toll_free, f2_high_npa, f3_voip,
        f4_repeated, f5_seq_asc, f6_all_same,
        f7_555, f8_last4zero,
        f9_invalid_nxx, f10_sub_all_same, f11_alternating,
        f12_seq_desc, f13_nxx_low, f14_low_entropy, f15_sub_seq,
        f16_time_sin, f17_time_cos, f18_geo_dist, f19_short, f20_plus_one,
    ]


def sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x)) if x >= 0 else (lambda e: e / (1.0 + e))(math.exp(x))


def logistic_regression_train(
    X: list[list[float]], y: list[int],
    lr: float = 0.05, epochs: int = 300, l2: float = 0.01
) -> tuple[list[float], float]:
    """Train logistic regression with L2 regularization via gradient descent."""
    n_features = len(X[0])
    weights = [0.0] * n_features
    bias = 0.0
    n = len(X)

    for epoch in range(epochs):
        grad_w = [0.0] * n_features
        grad_b = 0.0

        for xi, yi in zip(X, y):
            z   = sum(w * x for w, x in zip(weights, xi)) + bias
            err = sigmoid(z) - yi
            for j in range(n_features):
                grad_w[j] += err * xi[j]
            grad_b += err

        for j in range(n_features):
            weights[j] = weights[j] * (1 - lr * l2) - lr * grad_w[j] / n
        bias -= lr * grad_b / n

        if (epoch + 1) % 75 == 0:
            loss = 0.0
            for xi, yi in zip(X, y):
                p = max(1e-9, min(1 - 1e-9, sigmoid(sum(w * x for w, x in zip(weights, xi)) + bias)))
                loss -= yi * math.log(p) + (1 - yi) * math.log(1 - p)
            print(f"  Epoch {epoch+1}/{epochs}: loss={loss/n:.4f}")

    return weights, bias


def evaluate_logreg(X, y, weights, bias) -> dict:
    tp = tn = fp = fn = 0
    for xi, yi in zip(X, y):
        pred = 1 if sigmoid(sum(w * x for w, x in zip(weights, xi)) + bias) >= 0.5 else 0
        if pred == 1 and yi == 1: tp += 1
        elif pred == 0 and yi == 0: tn += 1
        elif pred == 1 and yi == 0: fp += 1
        else: fn += 1
    prec = tp / max(1, tp + fp)
    rec  = tp / max(1, tp + fn)
    f1   = 2 * prec * rec / max(1e-9, prec + rec)
    acc  = (tp + tn) / max(1, tp + tn + fp + fn)
    return {"precision": prec, "recall": rec, "f1": f1, "accuracy": acc,
            "tp": tp, "fp": fp, "tn": tn, "fn": fn}


def export_tree(tree) -> dict:
    """Convert a sklearn DecisionTreeRegressor's internal arrays to plain lists."""
    t = tree.tree_
    return {
        "feature": t.feature.tolist(),
        "threshold": [round(float(v), 6) for v in t.threshold],
        "children_left": t.children_left.tolist(),
        "children_right": t.children_right.tolist(),
        "value": [round(float(v[0, 0]), 8) for v in t.value],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default=str(OUTPUT_FILE))
    args = parser.parse_args()

    print("=== CallShield Spam Model Trainer v3 (GBT + LR fallback, 20 features) ===\n")

    if not DB_FILE.exists():
        print(f"ERROR: {DB_FILE} not found. Run import_all_sources.py first.")
        return

    with open(DB_FILE) as f:
        db = json.load(f)

    spam_numbers = [n["number"] for n in db["numbers"] if n.get("number")]
    print(f"Spam examples available: {len(spam_numbers):,}")

    # Negative examples — common legitimate US NPA codes with random subscribers
    legit_npas = [
        "212", "203", "312", "305", "214", "206", "503", "412",
        "216", "313", "804", "901", "701", "406", "605", "304",
        "207", "802", "603", "307",
    ]
    random.seed(42)
    negative_numbers = []
    spam_set = set(spam_numbers)
    target_negatives = min(len(spam_numbers), 50000)

    while len(negative_numbers) < target_negatives:
        npa = random.choice(legit_npas)
        nxx = str(random.randint(200, 899))   # Valid NXX range
        sub = str(random.randint(1, 9998)).zfill(4)
        num = f"+1{npa}{nxx}{sub}"
        if num not in spam_set:
            negative_numbers.append(num)

    print(f"Negative examples: {len(negative_numbers):,}")

    # Cap positive examples to balance dataset
    train_spam = spam_numbers[:50000]

    X: list[list[float]] = []
    y: list[int] = []

    for num in train_spam:
        X.append(extract_features(num))
        y.append(1)
    for num in negative_numbers:
        X.append(extract_features(num))
        y.append(0)

    combined = list(zip(X, y))
    random.shuffle(combined)
    X, y = [c[0] for c in combined], [c[1] for c in combined]

    split = int(len(X) * 0.8)
    X_train, X_test = X[:split], X[split:]
    y_train, y_test = y[:split], y[split:]

    print(f"\nTraining on {len(X_train):,} examples, testing on {len(X_test):,}")

    # ── Part 1: Train Gradient-Boosted Tree ensemble ──────────────────
    print("\n--- Gradient-Boosted Trees (n_estimators=50, max_depth=4, lr=0.1) ---\n")

    X_train_np = np.array(X_train)
    y_train_np = np.array(y_train)
    X_test_np  = np.array(X_test)
    y_test_np  = np.array(y_test)

    gbt = GradientBoostingClassifier(
        n_estimators=50,
        max_depth=4,
        learning_rate=0.1,
        min_samples_leaf=10,
        random_state=42,
    )
    gbt.fit(X_train_np, y_train_np)

    # Evaluate GBT
    y_train_pred = gbt.predict(X_train_np)
    y_test_pred  = gbt.predict(X_test_np)
    y_test_proba = gbt.predict_proba(X_test_np)[:, 1]

    train_prec = precision_score(y_train_np, y_train_pred)
    train_rec  = recall_score(y_train_np, y_train_pred)
    train_f1   = f1_score(y_train_np, y_train_pred)

    test_prec = precision_score(y_test_np, y_test_pred)
    test_rec  = recall_score(y_test_np, y_test_pred)
    test_f1   = f1_score(y_test_np, y_test_pred)
    test_auc  = roc_auc_score(y_test_np, y_test_proba)

    print(f"GBT Train — prec={train_prec:.3f}  rec={train_rec:.3f}  F1={train_f1:.3f}")
    print(f"GBT Test  — prec={test_prec:.3f}  rec={test_rec:.3f}  F1={test_f1:.3f}  AUC-ROC={test_auc:.3f}")

    # Export trees
    trees = []
    for estimators_at_stage in gbt.estimators_:
        tree_regressor = estimators_at_stage[0]  # binary classification → 1 tree per stage
        trees.append(export_tree(tree_regressor))

    # ── Part 2: Train logistic regression fallback ────────────────────
    print("\n--- Logistic Regression fallback (300 epochs, L2=0.01) ---\n")

    lr_weights, lr_bias = logistic_regression_train(X_train, y_train, lr=0.05, epochs=300, l2=0.01)

    lr_train_m = evaluate_logreg(X_train, y_train, lr_weights, lr_bias)
    lr_test_m  = evaluate_logreg(X_test, y_test, lr_weights, lr_bias)

    print(f"\nLR Train — acc={lr_train_m['accuracy']:.3f}  prec={lr_train_m['precision']:.3f}  rec={lr_train_m['recall']:.3f}  F1={lr_train_m['f1']:.3f}")
    print(f"LR Test  — acc={lr_test_m['accuracy']:.3f}  prec={lr_test_m['precision']:.3f}  rec={lr_test_m['recall']:.3f}  F1={lr_test_m['f1']:.3f}")

    # ── Build output JSON ─────────────────────────────────────────────
    # Build fallback_weights dict: feature_name -> weight
    fallback_weights = {name: round(w, 6) for name, w in zip(FEATURE_NAMES, lr_weights)}

    output = {
        "version": 3,
        "model_type": "gbt",
        "description": "CallShield GBT spam scorer v3 — 50 trees, 20 features, LR fallback",
        "threshold": 0.7,
        "n_estimators": 50,
        "learning_rate": 0.1,
        "trees": trees,
        "feature_names": FEATURE_NAMES,
        "fallback_weights": fallback_weights,
        "fallback_bias": round(lr_bias, 6),
        "gbt_metrics": {
            "train": {"precision": round(train_prec, 4), "recall": round(train_rec, 4), "f1": round(train_f1, 4)},
            "test": {"precision": round(test_prec, 4), "recall": round(test_rec, 4), "f1": round(test_f1, 4), "auc_roc": round(test_auc, 4)},
        },
        "lr_metrics": {
            "train": lr_train_m,
            "test": lr_test_m,
        },
    }

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(output, f, indent=2)

    print(f"\nModel saved to: {out_path}")
    print(f"\n  GBT trees:  {len(trees)}")
    print(f"  Features:   {len(FEATURE_NAMES)}")
    print(f"  Threshold:  0.7")

    print("\nLogistic regression fallback weights:")
    for name, w in zip(FEATURE_NAMES, lr_weights):
        print(f"  {name:30s}: {w:+.4f}")
    print(f"  {'bias':30s}: {lr_bias:+.4f}")


if __name__ == "__main__":
    main()
