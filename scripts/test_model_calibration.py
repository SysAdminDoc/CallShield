import unittest

import numpy as np

from train_spam_model import calibrate_threshold


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


if __name__ == "__main__":
    unittest.main()
