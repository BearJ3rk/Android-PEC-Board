# PEC Board V0.3

An offline-first Android picture exchange communication board. It offers large visual cards, categories, sentence construction, undo/clear controls, and Android text-to-speech.

V0.3 adds Settings-based card creation and removal, phone image selection for custom cards, persistent custom category tabs, and restoration of removed built-in cards. User-created cards, categories, and card visibility choices remain stored when updating the APK.

## Run

Open this folder in Android Studio (JDK 17), allow Gradle sync, and run the `app` configuration on an Android 6.0+ device or emulator.

Every approved push to `main` is compiled and signed by GitHub Actions. Download the installable release APK from the latest successful **Android build** workflow run under **Actions → Artifacts**, or from the repository's Releases page.

The in-app Settings panel can check this repository for a newer release, download its APK, and open Android's installer. All releases after V0.1 must use the same permanent signing key for update compatibility.

## Accessibility and privacy

- Large touch targets and high-contrast labels
- TalkBack descriptions for every communication card
- Works without an account or internet permission
- No ads, analytics, or data collection

Emoji are placeholders for an MVP. For clinical or classroom use, replace them with a consistent, licensed symbol library and personalize vocabulary with the user's support team.
