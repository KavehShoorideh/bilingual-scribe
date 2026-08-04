# Build and install

How to get Bilingual Scribe from source onto the Fairphone 4, and how updates
reach the phone afterwards.

## Toolchain

No Android Studio required. On macOS:

```sh
brew install openjdk@17                    # formula, not the temurin cask —
                                           # the cask's .pkg needs interactive sudo
brew install --cask android-commandlinetools
brew install --cask android-platform-tools # adb, fastboot

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --sdk_root=$ANDROID_HOME --licenses
sdkmanager --sdk_root=$ANDROID_HOME \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
  "ndk;28.2.13676358" "cmake;3.31.6"
```

Then write `android/local.properties` (gitignored):

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

The NDK and CMake are only needed from M1a onward (whisper.cpp), but they are
installed up front so the native build doesn't stall on a toolchain download.

### Gradle version is load-bearing

Use `./gradlew` — it is pinned to **Gradle 8.13**. Gradle 9.6+ removed the
`InternalProblems` API that AGP 8.13 still depends on, so a system `gradle` of
9.x fails at configuration time with:

> Plugin 'com.android.internal.application' relies on
> 'org.gradle.api.problems.internal.InternalProblems', a Gradle internal API
> that was removed in Gradle 9.6.0.

If you ever need to regenerate the wrapper, bootstrap it from a scratch
directory (a Gradle 9 daemon cannot configure this project long enough to run
the `wrapper` task against it), then re-run `./gradlew wrapper` with 8.13.

## Build and test

```sh
cd android
./gradlew :core:test              # pure JVM, no SDK needed
./gradlew :data:testDebugUnitTest # Robolectric
./gradlew :app:assembleDebug
bash scripts/ci-build.sh          # all of the above + the GMS-free check
```

The debug APK is large (~64 MB) because `compose-material-icons-extended`
contributes tens of megabytes of vector icons and debug builds are not
shrunk. The release APK is smaller (~47 MB) and will shrink much further once
R8 is enabled after the M1a JNI surface settles.

## Installing on the Fairphone 4

The FP4 is `arm64-v8a` and ships Android 13+, so `minSdk 33` is satisfied.
/e/OS has no Play Services — expect no Play Protect prompts.
`scripts/check-gms-free.sh` keeps the runtime classpath GMS-free in CI.

On the phone: **Settings → About phone → tap Build number 7×**, then
**Developer options → USB debugging**. Also allow **Install unknown apps** for
whichever installer you use.

```sh
adb devices                       # accept the RSA prompt on the phone
./gradlew :app:installDebug       # the inner dev loop
adb logcat -s RecordingService    # trace the capture service
```

Wireless, for hands-free recording tests — pair over USB first:

```sh
adb tcpip 5555
adb connect <phone-ip>:5555
```

## Shipping updates: GitHub Releases + Obtainium

`versionCode` is derived from the git commit count, so it rises monotonically
and every build is upgradeable over the last. Obtainium compares `versionCode`
to decide whether a release is newer, so this must never be hand-maintained.

### One-time setup

Signing key material lives **outside the repository** — never commit it:

- Keystore: `~/.android/bscribe-release.jks` (mode 600)
- Credentials: `~/.gradle/gradle.properties`, keys `bscribe.keystore`,
  `bscribe.keystore.password`, `bscribe.key.alias`, `bscribe.key.password`

CI reads the same values from GitHub **Actions secrets** (encrypted and
run-scoped — the correct place for them, unlike the repo):
`BSCRIBE_KEYSTORE_BASE64`, `BSCRIBE_KEYSTORE_PASSWORD`, `BSCRIBE_KEY_ALIAS`,
`BSCRIBE_KEY_PASSWORD`.

> **Back up the keystore.** If it is lost, no installed copy of the app can
> ever be upgraded in place again — every user must uninstall (destroying the
> recordings database) and reinstall.

When the keystore is absent the release build stays *unsigned* rather than
falling back to the debug key. That is deliberate: a debug-signed APK cannot
upgrade a release-signed install, so a silent fallback would produce an
artifact that fails to install.

### Cutting a release

```sh
git tag v0.2.0-m1a
git push origin v0.2.0-m1a
```

`.github/workflows/release.yml` runs the unit tests, assembles a signed
release APK, verifies the signature with `apksigner`, and attaches the APK to
a GitHub Release.

### On the phone

Install **Obtainium** from F-Droid, add
`https://github.com/KavehShoorideh/bilingual-scribe` as a source. It polls
Releases and offers each new version.

**One-time gotcha:** a debug-signed install cannot be upgraded by a
release-signed APK — the signatures differ. When switching from
`installDebug` to Obtainium you must uninstall first, which wipes the
database. Do the switchover before you have recordings worth keeping.
