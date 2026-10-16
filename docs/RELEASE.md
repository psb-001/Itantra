# Release process — how iTantra becomes an installable APK

**Scope: sideload only.** iTantra is a hackathon project for SIH26173. It will never be
uploaded to Google Play, so this document covers what actually applies to us: one signed
APK, installable by hand on a test phone — plus the bookkeeping that keeps that possible
a second time. Everything Play-specific (App Bundle, Play App Signing, targetSdk policy,
privacy forms) is deliberately out of scope and marked as such where it comes up.

---

## Identity — the names, written down once

| Thing | Value | Where it lives |
|---|---|---|
| App name (launcher, Settings) | `iTantra` | `res/values/strings.xml` → `app_name` |
| Release package | `com.bjp.itantra` | `applicationId` in `app/app/build.gradle.kts` |
| Debug package | `com.bjp.itantra.debug` | `applicationIdSuffix = ".debug"` |
| Version | `1.0.0`, versionCode `2` | `defaultConfig` in `app/app/build.gradle.kts` |
| Launcher icon | navy + mic + waves, adaptive with monochrome layer and PNG fallbacks | `res/mipmap-*`, source in `app/branding/` |
| About-screen version | read from the installed package at runtime | `AboutActivity.installedVersion()` |

The debug suffix is not cosmetic. It lets a debug APK and the sideloaded release sit on
the same phone at the same time; without it, installing one over the other fails on the
signature check and the only fix is uninstalling, which deletes the app's logs and
pending-message state. The trade-off is that the two builds keep **separate** log files
and separate `/sdcard/Android/data/<package>/` folders — `app/pull_logs.sh` tries the
release package first, then the debug one, so pulling logs does not need you to remember
which build a phone is running.

**Version policy.** `versionName` is what humans see; `versionCode` is what Android
compares when deciding whether one APK may update another. **Bump `versionCode` by one
on every build that goes onto a phone**, even a one-line fix; reuse it and update
installs silently fail on some devices. `versionName` follows the usual major.minor.patch
reading. The first proper release is `1.0.0` / code `2` — code `1` was the unreleased
0.1.0 development line, so the release is always "newer than anything that was installed
before it".

---

## Signing — read this before touching anything

Two files carry the app's identity, and **neither is in git**:

| File | What it is |
|---|---|
| `app/app/keystore/itantra-release.jks` | the RSA 4096-bit key (valid to 2054) |
| `app/keystore.properties` | the password to that key + the path to it (referenced from `build.gradle.kts`) |

**Back both of them up somewhere that is not this laptop.** Losing them means no build
you ever make again can update an installed iTantra — Android will refuse every install
over it with "App not installed", and the only path forward is uninstall-and-reinstall,
which wipes the phone's log files and pending messages. This is not a hypothetical: it
is the most common self-inflicted wound in Android release engineering.

**History, so nobody trips over it later.** The first keystore (Sep 2026) was committed
to git along with its password in `build.gradle.kts`. That key was rotated out on
16 Oct 2026, before any build signed with it had been distributed beyond local testing;
it is archived beside the new one as `itantra-2026-09-superseded.jks` and its password
remains in git history. It must not be used again — and the `.gitignore` now refuses
`*.jks` and `keystore.properties` so this cannot repeat. If you ever need to *rotate*
again (e.g. before any public distribution), generate a new key, keep the old file under
a dated name, and accept that every phone must uninstall once.

Inspect the current key without opening the keystore file:

```bash
~/android-dev/jdk17/Contents/Home/bin/keytool -list -v \
  -keystore app/app/keystore/itantra-release.jks \
  -storepass "$(sed -n 's/^storePassword=//p' app/keystore.properties)" | head -20
```

That command keeps the password out of your shell history — it is read straight from the
properties file — but the file itself is the secret. Do not paste its contents into chat,
commit messages, screenshots of this folder, or the deck.

The key's identity, pinned so any future build can be checked against it:

- Certificate DN: `CN=iTantra, OU=Team BJP, O=SIH26173, L=Pune, ST=Maharashtra, C=IN`
- Certificate SHA-256: `D9:C0:B3:1C:F8:68:90:61:F3:B6:F1:42:80:9B:E0:85:FE:6C:4C:67:6B:2F:79:6B:77:08:0C:D5:CA:9C:58:B9`

A healthy build's `apksigner verify --print-certs` prints that digest. If it prints a
different one, you are looking at a different key — stop and find out why before sideloading.

---

## Building

```bash
cd app
./build.sh assembleRelease
```

`build.sh` pins the toolchain this machine actually has (JDK 17, Android SDK and Gradle
under `~/android-dev/`) and forwards the first argument to Gradle. The signed APK lands
at `app/app/build/outputs/apk/release/app-release.apk`.

If the build fails with *"keystore.properties is missing — release builds cannot be
signed"*, you are missing one of the two backup files from the section above. Restore it;
do not "fix" it by generating a new key.

Two deliberate choices in `build.gradle.kts`, both with their reasoning in-file:

- **R8/ProGuard minification stays off.** The sherpa-onnx JNI layer loads Java classes by
  name, and shrinking is exactly the tool that renames them. The APK is a gigabyte of
  model binaries; minifying the ~500 KB of Kotlin in it saves nothing worth an untested
  runtime path.
- **`targetSdk` stays 33.** Play would require current-SDK targeting; sideload does not.
  The upgrade is a real task with real behaviour changes (foreground-service types,
  exact-alarm rules) and it gets done as its own *tested* change, not as a footnote to a
  release.

---

## Verifying a build before it goes on a phone

Run all four; each answers a different question.

```bash
BT=~/android-dev/android-sdk/build-tools/34.0.0
APK=app/app/build/outputs/apk/release/app-release.apk

# 1. Signed? By the right key?
"$BT/apksigner" verify --print-certs "$APK"

# 2. Zip structure ready for Android 15's 16 KB page devices?
#    Native libs are stored compressed, so no uncompressed .so exists for the alignment
#    rule to catch — a formality, and the reason -p (not -P 16; that spelling arrives with
#    build-tools 35) is enough here.
"$BT/zipalign" -c -p -v 4 "$APK" | tail -1

# 3. What will the phone actually show? label, version, icon set.
"$BT/aapt2" dump badging "$APK" | grep -E "package:|application-label|application-icon-|sdkVersion|targetSdkVersion" | head

# 4. The checksum for the handoff notes.
shasum -a 256 "$APK"
```

Then copy the artifact to the releases folder with a versioned name, and keep the
checksums beside it:

```bash
cp "$APK" releases/iTantra-1.0.0-release.apk
shasum -a 256 releases/*.apk > releases/SHA256SUMS.txt
```

`releases/` is gitignored — the binaries are shipped as GitHub Release assets, never
pushed into history. The checksum of the current release is also recorded in
`docs/DELIVERY_FINDINGS.md`, which is committed; that is how a hash becomes part of the
project record.

---

## Installing on a phone (sideload)

```bash
~/android-dev/android-sdk/platform-tools/adb install -r releases/iTantra-1.0.0-release.apk
```

or copy the file to the phone and open it with the Files app (allow "install unknown
apps" for that file manager once).

**Uninstall any older iTantra first** (Settings → Apps). Two reasons, either one is
fatal to an update-install:

1. the Sep 2026 key rotation changed the signature, and
2. a debug build has the same package name only if it predates the `.debug` suffix.

Android's installer can only phrase this as the famously unhelpful *"App not installed"*,
so: when in doubt, uninstall, then install.

After install, open the app once and check the **About** screen (version reads
`1.0.0 (release)`) and **Settings** (same string, plus the log-file path). If Settings
displays a version you did not build, you are looking at an old install.

---

## What this process still does not do

Listed so nobody mistakes the setup for more than it is:

- **No signing for the checksums and no release notes file** — the hash is recorded in
  the findings doc, not in a signed manifest.
- **The keystore is sideload-grade**: self-signed, one local copy. Before any public
  distribution, generate a real keypair, store it offline, and treat every phone's
  installed copy as needing a one-time uninstall.
- **No CI.** The build runs on the machine that holds the key, by design, until there is
  a reason to do it differently.
