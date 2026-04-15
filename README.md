# LeitnerBox

LeitnerBox is a simple Android flashcard app built with Jetpack Compose. It uses a Leitner-style spaced repetition flow to help users review cards when they are due.

## Features

- Multiple decks for organizing cards by topic
- Add, edit, search, and delete cards
- Duplicate protection when adding or importing cards
- Undo after card deletion
- Filter cards by box
- Practice mode with promotion and demotion across Leitner boxes
- Session summary after review with:
  correct and incorrect counts
  promoted and demoted cards
  next due load
- CSV import and export support
- Due-card reminders using Android notifications
- Shared top-right overflow menu across screens

## Tech Stack

- Kotlin
- Jetpack Compose
- Material 3
- AndroidX WorkManager
- Local persistence with `SharedPreferences`

## Requirements

- Android Studio
- Android SDK 36
- Minimum Android version: API 24
- Java 11

## Run Locally

1. Open the project in Android Studio.
2. Let Gradle sync the project.
3. Run the app on an emulator or Android device.

You can also build from the command line:

```powershell
.\gradlew.bat assembleDebug
```

## Import and Export

CSV import expects this header:

```csv
front,back
```

If an imported or manually added card has the same `front` and `back` as an existing card in the selected deck, the app warns the user and skips that duplicate.

## Notifications

The app schedules a reminder when cards become due. On Android 13 and above, it requests notification permission before showing due-card alerts.

## Project Structure

- `app/src/main/java/com/example/leitnerbox/MainActivity.kt`: main Compose UI, deck/card management, practice flow, import/export logic
- `app/src/main/java/com/example/leitnerbox/DueCardsReminderWorker.kt`: due reminder scheduling and notification delivery

## Status

The project currently builds with:

```powershell
.\gradlew.bat assembleDebug
```
