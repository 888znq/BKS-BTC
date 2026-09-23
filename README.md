# Multiplier Dashboard (Android)

Native Android shell around the original single-file HTML app.
The web app is bundled **unchanged** at `app/src/main/assets/index.html`.

## Build the APK on GitHub
1. Create a **private** GitHub repo and push the contents of this folder to `main`.
2. Open the repo → **Actions** → *Build APK* (it runs on every push; you can also press *Run workflow*).
3. When it finishes, open the run and download the **MultiplierDashboard-apk** artifact (zip containing `MultiplierDashboard.apk`).
4. Install on your phone (allow "install unknown apps").

## Build locally
`./gradlew assembleRelease` -> `app/build/outputs/apk/release/app-release.apk`

## Notes
- Package: `com.multiplier.dashboard`, minSdk 24, targetSdk 34.
- Signed with `keystore/release.jks` (password `multiplier123`, alias `multiplier`) so new builds install over old ones.
- To change the app, edit `assets/index.html` and push again.
