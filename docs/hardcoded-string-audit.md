# Hardcoded String Audit

Last verified: 2026-05-17

## Scope

- Audited `app/src/main/java/com/sysadmindoc/callshield` after the Hilt helper refactor.
- Live source count at audit time: 100 tracked main Kotlin files.
- Audit command used:
  - `rg -n '"([^"\\]|\\.){2,}"' app/src/main/java/com/sysadmindoc/callshield -g '*.kt'`
  - Follow-up targeted scans for direct Compose text, notification text, toasts, chooser titles, clipboard labels, result messages, and tile labels.

## Fixed In This Pass

- Moved backup/restore chooser titles, subjects, and restore result messages into `strings.xml`.
- Moved blocklist import/export chooser titles, subjects, import failures, and import-count plurals into resources.
- Moved sync success, warning, fallback, and error messages out of `SyncRepository` literals and into resources.
- Moved Quick Settings tile subtitle text into resources.
- Moved CSV log export and spam-share chooser/subject/body copy into resources.
- Moved clipboard labels shared by detail, blocked-log, recent-call, and FTC-report flows into resources.
- Moved number-detail action descriptions, report issue templates, confidence suffix, and report-count plural formatting into resources.
- Moved global-search result report-count formatting and recent-call duration formatting through resources/plurals.

## Remaining Literal Buckets

- Internal protocol/data tokens: match sources, source names, JSON keys, WorkManager names, DataStore keys, intent extras, file names, URLs, regexes, SQL sort clauses, and certificate/network host identifiers.
- User-generated or feed-provided content displayed as data: database descriptions, match reasons, spam types, contact names, locations, SMS bodies, and imported rule descriptions.
- Compose animation labels and test tags: retained as diagnostics, not visible app copy.
- Static `ChangelogScreen` release-note entries: visible but intentionally left for a separate resource-table or asset-backed changelog pass because it is a large historical content block.
- `BlockReasoning` English templates: visible decision-explanation copy. The safe follow-up is to convert the reasoning engine from preformatted strings to resource-key templates, ideally together with the rule replay / explainability work so dynamic bullets stay structured.
- `CommunityContributor` result messages: visible through detail contribution feedback. The safe follow-up is to return typed contribution outcomes from the network helper and let UI/ViewModel layers format resource-backed messages.
- 12-hour `AM`/`PM`, hour-window, and phone-number presentation helpers: leave for roadmap 1.8.3, which explicitly covers number/time localization.

## Status

Roadmap 1.8.1 is complete: the main source set has been audited, clear low-risk user-facing stragglers were moved to resources, and the remaining string debt is categorized for the next localization/explainability passes.
