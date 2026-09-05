# Translating CallShield

CallShield ships English only today. Translations are welcome, and partial ones
are genuinely useful. Android falls back to English per string, so a translation
that covers the screens people actually look at is better than none.

The general translation thread is
[issue #7](https://github.com/SysAdminDoc/CallShield/issues/7). Comment there to
claim a language so two people don't translate the same 1,103 strings.

## What to translate

The source of truth is `app/src/main/res/values/strings.xml` (1,103 strings and
30 `<plurals>`). Create `app/src/main/res/values-<locale>/` and put your
translated `<string>` and `<plurals>` elements there. Either layout works ,
everything in one `strings.xml`, or split into `strings.xml` + `plurals.xml`;
resource filenames carry no meaning to the Android build.

Locale directory names use Android's qualifier form, not BCP-47:

| Language | Directory |
|---|---|
| Simplified Chinese | `values-zh-rCN` |
| German | `values-de` |
| Brazilian Portuguese | `values-pt-rBR` |

**Priority order**, if you are not translating everything:

1. **Call screening and blocking**. The dashboard, the blocked log, the
   after-call notification. This is what users see when the app does its job.
2. **Onboarding and permissions**. The recovery paths. A user who cannot read
   why call screening is off cannot fix it.
3. **Blocklist and rules**. Day-to-day management.
4. **Settings, statistics, changelog**. These can come later.

## Register the locale

Add your language to `app/src/main/res/xml/locales_config.xml`:

```xml
<locale android:name="zh-CN" />
```

Note the BCP-47 form here (`zh-CN`), which differs from the resource directory
(`values-zh-rCN`). The manifest sets `android:localeConfig`, so this file is what
populates the per-app language picker in Android's system settings. Skip it and
your translation only appears for users whose entire device is set to that
language. It is the single easiest thing to forget.

## Check your work

```bash
python scripts/check_translations.py                  # every locale in the repo
python scripts/check_translations.py --locale zh-rCN  # just yours
python scripts/check_translations.py --dir path/to/values-xx  # before it's in the repo
```

It reports coverage and fails on the things that break at runtime rather than at
build time:

- **Format specifiers that don't match the English.** `%1$s`, `%d` and friends
  must survive translation with the same count and types. Get this wrong and
  `String.format` throws the moment the string is displayed. Which in this app
  can be the call-screening notification, on a device in a language the
  maintainer cannot read. Reordering is fine and expected; that is what the
  positional `%1$s` form is for.
- **Missing plural quantities.** Chinese needs only `other`; Russian and Polish
  need `one/few/many/other`. The checker knows which.
- **Stale keys** that no longer exist upstream (a warning, not an error).
- **Locales missing from `locales_config.xml`.**

Missing strings are reported as coverage, never as a failure - with one
exception. Each shipped locale has a recorded coverage floor in
`scripts/translation_floors.json`, and the checker fails if coverage drops
below it. A partial translation is fine and expected; a translation quietly
rotting as English strings are added faster than they are translated is not.

If you improve a locale, raise its floor in the same change:

```bash
python scripts/check_translations.py --update-floors
```

The floor only ever goes up. Lowering one is a deliberate decision that
belongs in the commit message. A new locale starts with no floor and only
warns until someone records one.

## Style notes

- Phone numbers are wrapped in Unicode bidi isolates before display, so you do
  not need to do anything special for RTL languages. But do keep `%1$s`
  placeholders intact rather than substituting a literal example number.
- "Trusted" is used consistently for allowlisted callers; keep one term for it
  in your language rather than alternating synonyms.
- Carrier authentication (STIR/SHAKEN) copy deliberately avoids implying the
  caller is *safe*. It says the caller ID is *authenticated*. Please preserve
  that distinction, it is a correctness matter and not a stylistic one.
- Keep `CallShield` untranslated as the app name.

## Submitting

Open a PR with just the resource files and the `locales_config.xml` line. Please
don't include build outputs, APKs, or version bumps. Those come from the release
process and only make the diff hard to review. Your commits keep your authorship.
