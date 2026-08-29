# CallShield visual QA

Status: passed

## Test setup

- Reference mockups: `docs/design/reimagined-2026-08-29/*.png`
- Captured implementation: `docs/design/reimagined-2026-08-29/implemented/*.png`
- Paired comparisons: `docs/design/reimagined-2026-08-29/comparison/*.png`
- Reference size: 853 x 1844 pixels
- Test device: Pixel 6 Android emulator, API 35, 1080 x 2400 pixels
- Comparison method: implementation captures were resized and cropped to the reference frame, then placed beside the matching mockup

## Results

| Page | Result | Notes |
| --- | --- | --- |
| Home | Passed | Live protection, weekly totals, setup state, engine count, database count, and check rows follow the reference hierarchy. |
| Activity, recent | Passed | Header, tabs, summary strip, refresh action, and empty state match. The reference contains sample activity while the test device has none. |
| Activity, blocked | Passed | Filters, selected tab, empty state, and navigation align. The reference contains sample blocked records while the test device is clean. |
| Lookup | Passed | Search controls, privacy note, risk gauge, assessment details, pipeline trace, and actions use the reference system. Live database results make the detail card taller. |
| Rules | Passed | Manual protection summary, rule tabs, import action, empty state, and fixed add action match. The reference uses sample blocked numbers. |
| More | Passed | Protection status, database metrics, appearance selector, tool cards, release actions, and section rhythm align. |
| Settings | Passed | Access summary, required checks, optional access row, appearance controls, and blocking settings match. Longer production labels wrap without clipping. |
| Statistics | Passed | Snapshot, metric cards, totals strip, and activity panel align. The reference uses sample trends while the clean device shows its intended no-data state. |
| Protection test | Passed | System-check header, progress, test action, results, and recommended actions follow the reference layout. The emulator passed 25 of 28 environment-dependent checks. |
| What's new | Passed | Latest-release card, release summary, history timeline, spacing, and type hierarchy match. |
| Onboarding | Passed | Guided progress, privacy message, setup promise, benefit card, and primary action align. Two checks were already available on the emulator. |
| Number details | Passed | Phone identity, risk assessment, actions, detection reasons, statistics, online lookup, and reporting controls use the reference hierarchy. Live lookup data determines the score and block state. |

## Iteration record

1. Reworked the shared color, typography, border, divider, spacing, and navigation treatment.
2. Fixed inner-page insets, lookup privacy placement, settings value wrapping, and compact tool-card spacing after the first capture pass.
3. Tightened the lookup and number-detail risk hierarchy against populated results.
4. Re-captured every page, paired it with its reference, and reviewed the complete contact sheet.

The remaining visible differences come from real device state, system bars, live database values, and Android environment checks. No clipped controls, broken navigation, overlapping text, or unreachable primary actions were found.
