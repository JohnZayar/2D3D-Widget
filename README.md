# Myanmar 2D — Android Studio Project

Kotlin + Jetpack Compose app that reproduces the look of the reference
screenshots: a big current 2D number, red result cards showing SET / Value /
2D per session, a 2D results history list, and a 3D results history list.

## Pushing to GitHub

This folder is a plain (not yet initialized) git project. On your own
machine, with internet access:

```bash
cd Myanmar2DApp
git init
git add .
git commit -m "Initial commit: Myanmar 2D app"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

Note: the `gradle/wrapper/gradle-wrapper.jar` binary isn't included (this
project was generated without internet access). The first time you open the
project in **Android Studio**, it will offer to regenerate the wrapper
automatically — accept that, then it's fine to commit the resulting
`gradlew`, `gradlew.bat`, and `gradle-wrapper.jar` files. Alternatively, if
you have Gradle installed locally, run `gradle wrapper --gradle-version 8.7`
inside the project folder before your first commit.

A GitHub Actions workflow (`.github/workflows/build.yml`) is already set up
to build a debug APK automatically on every push to `main`, and to attach it
as a downloadable build artifact on the Actions run page — no wrapper jar
needed for CI since it installs Gradle directly.

## How to build the APK locally

1. Install **Android Studio** (Giraffe/Koala or newer) — free, from
   developer.android.com/studio.
2. `File → Open` → select this `Myanmar2DApp` folder.
3. Let Gradle sync (it will download the Gradle 8.7 wrapper + dependencies —
   needs internet the first time).
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`.
5. The APK lands in `app/build/outputs/apk/debug/app-debug.apk` — copy it to
   your phone (or `adb install app-debug.apk`) to test it.

To make a signed release APK: `Build → Generate Signed Bundle / APK`.

## Where the numbers come from

`Models.kt` contains `calculate2D(set, value)` — the formula that turns a
SET index + trading Value into the 2-digit number, reverse-engineered from
the example sessions in your screenshots:

| SET     | Value     | 2D |
|---------|-----------|----|
| 1588.82 | 35,776.44 | 26 |
| 1595.58 | 63,421.60 | 81 |
| 1580.67 | 38,571.69 | 71 |
| 1576.92 | 64,483.60 | 23 |

Rule: **digit 1** = last digit of SET's decimal part, **digit 2** = last
digit of Value's whole-number part.

`SampleData.kt` currently hard-codes those same sample numbers so the app
runs immediately with no network calls. To make it live, replace
`SampleData` with a real repository (e.g. an API/Retrofit call, or scraping
your own licensed data feed) that returns `TwoDResult`/`ThreeDResult` lists
— the UI and formula code don't need to change.
