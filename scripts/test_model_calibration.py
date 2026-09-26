import unittest

import numpy as np

from evaluate_model import resolve_holdout
from train_spam_model import calibrate_threshold, holdout_digest, holdout_manifest


class ModelCalibrationTest(unittest.TestCase):
    def test_maximizes_recall_with_precision_floor(self):
        labels = np.array([1, 1, 0, 0])
        probabilities = np.array([0.90, 0.70, 0.80, 0.10])

        threshold, predictions = calibrate_threshold(
            labels,
            probabilities,
            min_precision=0.66,
        )

        self.assertEqual(0.70, threshold)
        self.assertEqual([True, True, True, False], predictions.tolist())

    def test_rejects_unreachable_precision_floor(self):
        labels = np.array([1, 0])
        probabilities = np.array([0.50, 0.90])

        with self.assertRaises(ValueError):
            calibrate_threshold(labels, probabilities, min_precision=1.01)


class HoldoutManifestTest(unittest.TestCase):
    def test_the_evaluator_scores_exactly_the_rows_training_held_out(self):
        # Rebuilding the split from today's database mixed in rows the model was
        # trained on. Only the manifest's rows count, each under its own label.
        manifest = holdout_manifest(
            "2026-09-21T23:37:55+00:00",
            [(1, "+12125550101"), (1, "+12125550102"), (0, "+13125550103")],
        )
        self.assertNotIn("+12125550101", str(manifest), "the manifest must not list numbers")

        rows, coverage = resolve_holdout(
            manifest,
            positives=["+12125550101", "+12125550199", "+12125550102"],
            negatives=["+13125550103", "+13125550198", "+12125550101"],
        )

        self.assertEqual(
            sorted([("+12125550101", 1), ("+12125550102", 1), ("+13125550103", 0)]),
            sorted(rows),
        )
        self.assertEqual({"positives": 1.0, "negatives": 1.0}, coverage)

    def test_coverage_shows_rows_that_left_the_database(self):
        manifest = holdout_manifest("t", [(1, "+12125550101"), (1, "+12125550102"), (0, "+13125550103")])

        rows, coverage = resolve_holdout(manifest, positives=["+12125550102"], negatives=["+13125550103"])

        self.assertEqual([("+12125550102", 1), ("+13125550103", 0)], rows)
        self.assertEqual(0.5, coverage["positives"])
        self.assertIn(holdout_digest("+12125550101"), manifest["positives"])


if __name__ == "__main__":
    unittest.main()
