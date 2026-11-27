# Localization

All user-facing text lives in String Catalogs. English is the source language.

| Catalog | What it holds |
| --- | --- |
| `mobile/ios/Sources/Resources/Localizable.xcstrings` | App UI, toasts, errors, notification buttons, the relay's fallback notification text (`New activity`) |
| `mobile/ios/Sources/Resources/InfoPlist.xcstrings` | App name and permission prompts |
| `mobile/ios/NotificationService/Localizable.xcstrings` | Notification titles and bodies set by the extension |

## Adding a language

1. Open the project in Xcode, select a catalog, click **+** and pick the language (do this for all three catalogs).
2. Translate the entries. Plurals (for example `%lld steps`) have one form per plural category.
3. Build. Untranslated entries fall back to English.

## Writing code that stays localizable

- SwiftUI string literals (`Text("Done")`, `Button("Allow")`, `Toggle`, `Section`, `navigationTitle`) are localized automatically.
- Text built outside a view (toasts, errors, computed labels) uses `String(localized: "…")`. Write whole sentences with interpolation (`"Worked for \(time) · \(steps)"`), never glue translated pieces together.
- Helpers that take a label take `LocalizedStringKey`, not `String`.
- Content from the agent, chat titles, file names and model names are data: show them as-is.
- Brand names and symbols that never translate use `Text(verbatim:)`.
- Durations, percentages and dates go through formatters (`DateComponentsFormatter`, `.formatted(.percent)`), not hand-built strings.

After changing text, run `mobile/ios/scripts/sync_strings.sh` to refresh the catalogs.

To spot text that is not localized, run the app with the launch argument `-NSDoubleLocalizedStrings YES`: every localized string appears twice, so anything shown once is hard-coded.
