# Sentinel

An on-device check for Android monitoring software. One Activity, plain Java, no
libraries, no root, no computer needed to run it.

**This directory sits on a `claude/*` working branch and is unrelated to the
NemoClaw Community project.** It is here because it needed somewhere reachable
over HTTP and a standalone repository could not be created from this session.
Deleting the branch loses nothing else.

## Install

    adb install -r dist/sentinel.apk
    adb shell pm grant com.bluetier.sentinel android.permission.GET_APP_OPS_STATS

`dist/sentinel.apk` — 29061 bytes, sha256 `119bce99527322c89419ffbec9e1aa3a1ab7d98a87d8f82143f630c4444735ec`,
signed, verifies for API 30-35. Debug-signed, so uninstall any earlier copy first.

The grant is optional and the app works without it, but it is what reveals screen
capture, clipboard and overlay access. Those are app operations rather than
permissions, so they never appear in an app's permission list, and screen capture
reads messages after the app holding them has decrypted them. Without the grant
the app reports that those checks did not run.

## Build

`./build.sh` — javac, d8, aapt2, zipalign, apksigner. Needs a JDK and an Android
SDK with build-tools 35 and platform 35. No Gradle and no network.

## Test

    javac -d /tmp/e src/com/bluetier/sentinel/Rules.java eval/Tests.java eval/Eval.java
    java -cp /tmp/e Tests        # 27 regression tests
    java -cp /tmp/e Eval 4000    # false-positive and detection rates

`Rules.java` holds every decision and imports nothing from Android, so it runs on
an ordinary JVM. That is the only reason the rates below exist: while the rules
lived inside the Activity, their false-positive rate was a matter of opinion.

Measured over 4,000 synthetic devices per population, three seeds, under both
possible platform defaults for the clipboard app operation:

| Population | Any finding | Reads "suspicious" | Reads "strong" |
|---|---|---|---|
| Clean phones | 7.5-8.7% | 0.7-1.1% | 0.05-0.2% |
| Company-managed phones | 6.5-8.5% | 0.6-1.0% | 0-0.2% |
| Monitoring software, 6 variants | 100% | 100% | 3-100% |

Four rules were rewritten because the first measurement put clean phones at 34%
flagged and every managed phone at 100%:

- Any app operation on any sideloaded app fired on 33% of clean phones. F-Droid is
  allowed to install packages because that is what an app store does; Termux and
  VLC draw overlays. Being sideloaded was never the discriminating fact. Having no
  launcher icon is.
- A hidden app with two covert permissions was critical, on 2% of clean phones.
  Plenty of legitimate software has no launcher icon, and a sync client with
  background location and microphone access is not rare. Three is the threshold.
- Any device administrator was a finding, so every company-managed phone was told
  it was compromised. Recognised management agents are now configuration.
- Any hand-added certificate authority was a finding. Encrypted DNS and ad
  filtering both need one.

An operation held by more than half the ordinary apps on the device is treated as
a platform default rather than a capability anyone granted, and is not reported
per app. That calibration is measured on the device being scanned rather than
hard-coded, because the platform default for `android:read_clipboard` could not be
established off-device, and if it is "allowed" then a rule treating it as a
capability is worthless.

## What it cannot see

Stated in the app rather than left to be discovered.

- A cleanly uninstalled app is gone. What kept its data behind is recoverable.
- No su binary at the usual paths means very little: current root tools leave no
  file at any of them, and some of those directories cannot be read by an ordinary
  app, so a refusal and an absence are indistinguishable. Verified boot is the
  answer that carries weight.
- A name on a certificate proves nothing. Anyone can put any name on one.
- Traffic is not inspected; what is checked is the certificate that makes
  interception possible. For traffic itself use TrackerControl or NetGuard.

One claim is unverified: granting `GET_APP_OPS_STATS` over adb depends on that
permission carrying Android's `development` flag. The constant is not public in
the SDK and the framework manifest does not ship with it, so this could not be
confirmed. The grant either works or it does not, and the app reports which.

The percentages above are only as good as the synthetic device mix they were
measured against, which was estimated rather than sampled. The mechanism behind
each fix holds regardless; read the figures as the relative improvement they are.

## Credits and scope

Package-name indicators derive from the Echap stalkerware-indicators list,
CC-BY 4.0. No code is taken from Amnesty's MVT, whose licence carries a
consent-use restriction, or from Coalition Against Stalkerware material.

Scan only a device you own or have permission to examine. If your safety depends
on whoever installed monitoring software not knowing you looked, removing it tells
them; <https://stopstalkerware.org> has guidance written for that situation.
