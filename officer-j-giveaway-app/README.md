# Officer J's Auto Spa Giveaway App

Native Android giveaway application for Officer J's Auto Spa.

## Main features

- Multiple separately saved wheel spinners and raffles
- Unlimited saved giveaways and practical entry limits based on device storage
- Manual entry with quantity
- Automatic sequential entry numbers
- Bulk paste import using `Name`, `Name,5`, or `Name x5`
- TXT, CSV, and JSON import support
- CSV export for each giveaway
- Full-app JSON backup and restore
- Five-second animated wheel
- Five-second animated raffle box and winner slip
- Saved winner history
- Branded MP4 result video generation and Android Gallery export
- Offline local storage
- Separate Android package: `com.officerj.autospa.giveaway`

The approved visual target is included at `docs/approved-design-reference.jpg`.

## Fastest phone-only GitHub method

1. Download the project ZIP from ChatGPT.
2. On GitHub, create a new empty repository named `Officer-J-Giveaway`.
3. Do not add a README, license, or `.gitignore` when creating the repository.
4. Upload the ZIP file to the repository.
5. Open the repository, tap **Code**, open the **Codespaces** tab, and create a codespace on `main`.
6. Open the Codespaces terminal and run the commands below. Replace the ZIP filename only if it differs.

```bash
unzip officer-j-giveaway-app.zip
shopt -s dotglob
mv officer-j-giveaway-app/* .
rmdir officer-j-giveaway-app
rm officer-j-giveaway-app.zip
git add .
git commit -m "Build Officer J Giveaway app"
git push
```

7. Return to the repository's **Actions** tab.
8. Open **Build Android APK**.
9. Wait for the workflow to finish with a green checkmark.
10. Open the completed workflow, scroll to **Artifacts**, and download `Officer-J-Giveaway-APK`.
11. Extract the downloaded artifact ZIP and install `app-debug.apk` on the Android phone.

## Updating the app later

Replace changed project files in the codespace, then run:

```bash
git add .
git commit -m "Update giveaway app"
git push
```

Each push automatically builds a new APK through GitHub Actions.

## Direct Codespaces build

The included GitHub Actions workflow is the recommended build method because a normal Codespace does not always include the Android SDK. The workflow supplies Java and Gradle and builds the native APK automatically.

## Data storage

All giveaway data is stored locally inside the app. Uninstalling the app may delete local data, so use **EXPORT ALL** before uninstalling or moving to another device.
