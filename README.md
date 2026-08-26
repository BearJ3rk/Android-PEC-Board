# PEC Board V0.6

An offline-first Android picture exchange communication board. It offers large visual cards, categories, sentence construction, undo/clear controls, and Android text-to-speech.

V0.7 adds a new PEC Board app icon and lets you long-press and drag the category tabs to reorder them. The category order saves automatically and is included in board backups.

V0.6 added **Backup & Restore** to Settings. A complete board—including custom categories, cards, edited defaults, removed-card choices, drag ordering, and uploaded images—can be exported as one ZIP through Android's file picker. Choose Google Drive to keep the backup with a Google account, then restore it on this or a replacement phone. Restoring validates the backup before replacing the current board.

The backup file can contain personal images and is not encrypted by PEC Board, so keep it in a private location. User-created cards, categories, images, ordering, and visibility choices also remain stored during normal APK updates.

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
