# Security & privacy

## How the app handles data

| Data | Where it goes |
|---|---|
| Swims, heart rate, lengths, sleep, steps, HRV | Read from Health Connect (**read-only**) and stored in the app's private Room database on the phone. |
| Your profile, goals, tests, plan, notes | Same private database. Never uploaded. |
| Gemini API key | Encrypted with an AES-GCM key held in the **Android Keystore**; never logged or exported. |
| Coach chat | Only when you send a message: a text summary of your profile, plan and recent swims goes to Google's Gemini API using **your** key. |
| Update checks (if enabled in a build) | Public GitHub Releases API; no personal data sent. |

There is **no backend, no account, no analytics and no crash reporting**. `android:allowBackup` is off, so the database isn't copied into cloud backups. Exports (CSV/JSON) only happen when you choose a file location.

## What is *not* in this repository

- Signing keys and passwords: `keystore.properties`, `*.jks` and `*.keystore` are git-ignored.
- Machine paths: `local.properties` is git-ignored, and no absolute paths are committed.
- API keys or tokens of any kind.
- Personal health data: screenshots use the synthetic generator in `data/SampleData.kt`, which is only reachable in debug builds.

If you fork the project, keep it that way: never commit a keystore, a `local.properties` or a real API key.

## Permissions

| Permission | Why |
|---|---|
| `health.READ_*` (exercise, HR, distance, calories, speed, resting HR, sleep, steps, HRV, history, background) | To import swims and daily context. All read-only; sleep, steps and HRV are optional. |
| `INTERNET` | Gemini chat (optional) and update checks. |
| `POST_NOTIFICATIONS` | Gemini usage alerts and update notices. |
| `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | Optional self-update from GitHub Releases. Off unless a build sets `updateRepo`. |

## Reporting a vulnerability

Please **don't open a public issue**. Use GitHub's **private vulnerability reporting** (*Security → Report a vulnerability*) on this repository, with steps to reproduce. You'll get a reply as soon as possible.
