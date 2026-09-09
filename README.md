# Pinshot

Pinshot is an Android application designed to keep screenshot clutter under control. It automatically manages temporary screenshots by giving them a 24-hour expiration window before moving them to trash, while letting you pin important screenshots permanently.

## Key Features

- Auto Expiration: Unpinned screenshots expire after 24 hours and move to trash automatically.
- Pinning: Pin screenshots you want to keep forever with a single tap.
- Fast Scrubber: Custom ballistic timeline scrubber for fast navigation across months and years.
- Trash & Recovery: Dedicated trash screen to review, restore, or permanently remove screenshots.
- Notifications: Timely alerts before screenshots expire, with quick actions to pin all or review.
- System Integration: Direct integration with Android MediaStore and system trash permissions.

## Architecture & Tech Stack

- Language: Kotlin
- UI Framework: Jetpack Compose with Material 3
- Local Storage: Room Database
- Background Jobs: WorkManager
- Media Loading: Coil
- Architecture: MVVM with StateFlow

## Getting Started

### Prerequisites

- Android Studio Ladybug or newer
- JDK 17 or higher
- Android SDK (Target SDK 37, Min SDK 24)

### Building the App

1. Clone the repository:
   git clone https://github.com/escapebranch/pinshot.git
2. Open the project in Android Studio.
3. Sync Gradle project dependencies.
4. Build and run on a physical device or emulator running Android 7.0 (API 24) or higher.

## License

This project is licensed under the MIT License.
