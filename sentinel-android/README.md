# Sentinel

An on-device check for Android monitoring software. One Activity, plain Java, no
libraries, no root, no computer needed to run it.

**This directory sits on a `claude/*` working branch and is unrelated to the
NemoClaw Community project.** It is here because it needed somewhere reachable
over HTTP and a standalone repository could not be created from this session.
Deleting the branch loses nothing else.

## What this scan could check

The rule this project was built on: a scanner that silently finds nothing is
worse than one that admits it was blocked. Every check follows it; the self-audit
turns it on the whole app. A phone reports "no indicators found" for two very
different reasons - because it looked and is clean, or because it could not look -
and those must never read the same. So every scan opens with which of the app's
eyes are open on this device, which are shut and why, and a coverage figure, so a
clean verdict is worth exactly what you can see was behind it. On a MediaTek phone
without the app-operation grant, for instance, it reports 81% coverage with
screen-capture detection blind, and names the one adb command that fixes it -
rather than printing a green light over a check that never ran.

This now covers the always-on half too, not just the one-shot scan. The radio,
satellite and sensor watches each report their own attach state - live, refused
(with the one-line grant that fixes it), or never started - so a watch that was
silently refused can no longer hide behind a clean scan. It was not academic: six
days of real driving ran with the radio watch refused the whole time and nothing
said so, because the self-audit only looked at the scan. The satellite watch also
writes a periodic heartbeat to the radio log, so its silence is confirmable - a
line of satellites in view, or a plain statement that it is off - instead of the
nothing that a dead watch and a clean sky used to share.

## Install

    adb install -r dist/sentinel.apk
    adb shell pm grant com.bluetier.sentinel android.permission.GET_APP_OPS_STATS

`dist/sentinel.apk` — 70021 bytes, sha256 `ddf1338ebf2a12b798801f3333ea7587b0f4c78faab178b0fdd68b1a37451bb3`,
signed, verifies for API 31-35. Debug-signed, so uninstall any earlier copy
first - and always pull the phone's logs before you do, so an uninstall never
costs you data.

The radio watch starts on foreground location alone (it no longer waits on
background location, which used to stop it from starting at all) and asks to be
exempted from battery optimization when it starts, so Doze cannot stop it
mid-drive. `sentinel-pull.ps1` copies the logs into a fresh timestamped folder
and reports whether the watch is actually recording - run it before any
uninstall.

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
    java -cp /tmp/e Tests        # 38 regression tests, the installed-software rules
    java -cp /tmp/e RadioTests   # 24 regression tests, the radio rules
    java -cp /tmp/e ObservatoryTests  # 8 tests, the cell memory
    java -cp /tmp/e WifiTests    # 12 tests, the evil-twin rules
    java -cp /tmp/e WifiEval 2000 # false positives and detection, synthetic Wi-Fi
    java -cp /tmp/e GnssTests    # 9 tests, GPS spoofing and jamming
    java -cp /tmp/e GnssEval     # false positives and detection, synthetic skies
    java -cp /tmp/e SensorTests  # 12 tests, live sensor-access judgment
    java -cp /tmp/e CorrelatorTests  # 10 tests, cross-signal coincidence
    java -cp /tmp/e SelfAuditTests   # 11 tests, the coverage self-audit incl. the live watches
    java -cp /tmp/e GuardTests       # 8 assertion guards over the eval bounds
    java -cp /tmp/e BoundaryTests    # 14 threshold edge tests
    java -cp /tmp/e Eval 4000    # false positives and detection, synthetic devices
    java -cp /tmp/e DriveEval 500  # false positives and detection, synthetic drives

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

The test suite was mutation-tested (see MUTATION.md). The honest result: it
caught about 40% of injected faults, well below what the passing count
suggested. Most survivors are equivalent mutants; the genuine holes found -
an entirely untested notification path among them - are now covered or
recorded. No latent bug: every core detection path still fires at 100%.

## Watching the radio

A second mode runs while you drive and writes down what the radio did. It reads
the serving cell, its tracking area, the neighbouring cells, the technology in
use, and the band and downlink frequency, once every six seconds.

It cannot see the baseband's signalling. Rayhunter reads a Qualcomm diagnostic
interface and watches actual RRC messages; no ordinary Android app can reach
that, and the Android 15 APIs for null ciphering and identity disclosure
(`SecurityAlgorithmUpdate`, `CellularIdentifierDisclosure`) are privileged and
absent from the public SDK. What is left is consequences: a phone dragged onto
an older network, a cell that changed which tracking area it claims, neighbours
that vanished while the signal stayed strong.

No single one of those means anything. A tracking area changes every time you
cross town, rural roads fall back to 3G by themselves, and a voice call on a
network using circuit-switched fallback puts a phone on 3G at full signal for
the length of the call. So nothing is reported on its own: indicators score,
each kind counts once, and only a combination inside two minutes is worth
saying. The discriminating fact is not the downgrade but the signal strength
behind it, because a coverage hole is quiet and equipment close enough to
collect an identity is loud.

Measured over 20,000 synthetic drive-days per population, three seeds, including
about 2,000 trips to unfamiliar routes and roughly 35 carrier re-plans:

| Population | Drivers alerted in 20 days | Alerts per drive-day |
|---|---|---|
| Urban commute | 0% | 0 |
| Rural commute | 0% | 0 |

| A fake cell, after 20 days of ordinary commuting | Caught |
|---|---|
| Reusing a real cell id with a new tracking area | 100% (all high) |
| A wholly new cell | 79% |

The first is the realistic case, because equipment that wants phones to attach
to it imitates the network already there.

The watch keeps a persistent memory of every cell that has ever served this
phone - the network seen from below. An operator console sees every site from
above and asks which are down; a phone cannot see sites, only the cells that
broadcast to it, so the view from the ground is not a map but a memory, and the
question it answers is the inverse: is this cell one I know, or a stranger? That
memory changes what the same reading means. A tower you have stood under for
weeks shedding load is congestion and its odd behaviour is discounted; the same
behaviour on a cell you have never seen is corroborated. The distinction is the
handset-side echo of an operator's "not discovered / new site". A cell becomes an
anchor only after being seen across several sessions and places; until the phone
has learned a baseline, "never seen before" says nothing and does not score, so a
fresh install does not treat every cell on its first drive as suspect. And a cell
that reuses a known identity but claims a different tracking area is wearing that
cell's name rather than being it: its anchor trust is revoked for the session the
moment memory and the broadcast disagree, which is what stops an impersonator
from inheriting the very discount meant to quiet the real cell. The memory is
stored in the app's own space as plain lines and never leaves the phone.

A fourth indicator reads cell barring, which is the handset-side view of what a
carrier's own console labels "site locked or barred": a cell broadcasts that it
is turning certain services away, and `BarringInfo` reports which and whether the
bar is hard (unconditional) or a probability (conditional). Networks bar for
congestion constantly, so a bar on its own is never an alert; a conditional bar
never scores at all, and a hard bar counts toward the combination only once it
has held for several samples at a strong signal, because a congested tower bars
in flickers while a cell herding phones bars steadily. Two cases are reported on
their own: a bar on emergency calling, which a normal network almost never does
and which is a safety fact worth knowing wherever it appears; and, through the
combination, a sustained hard bar at a strong signal. Measured against the same
synthetic drives with ordinary congestion barring mixed in, this left the
false-positive rate at zero. The second is harder and the number is
lower; it is reported as it measured.

Six defects surfaced only because this was measured rather than reasoned about.
The first version alerted 100% of drivers at 8 to 23 alerts per day. Ranking
non-standalone 5G above LTE made every ordinary handover look like a
two-generation downgrade. The suspicion window tumbled on a fixed cadence
instead of sliding from the first piece of evidence, so two indicators forty
seconds apart could straddle a boundary and never meet. The neighbour rule asked
whether the previous sample had neighbours, which by the time a run was three
samples long could never be true. `lastAlertAt` started at `Long.MIN_VALUE` and
`now - lastAlertAt` overflowed negative, so the cooldown test was false and no
first alert could ever fire. The same indicator repeating was corroborating
itself, which alerted 40% of rural drivers. And a carrier re-planning an area
moves many cells at once where equipment is one cell, so counting how many
changed together separates them.

### What it costs

The radio watch asks for location permission, because Android puts cell identity
behind it. It is worth being blunt about that in an app whose subject is software
that watches people. It never requests a position fix and never records a
coordinate; "where you are" is represented by which cells are nearby, not by
where they are. Everything is written to the app's private storage.

The app declares no `INTERNET` permission, which you can confirm in Settings or
with `aapt dump badging`. An app without it cannot open a network connection, so
none of this can be sent anywhere, by this app or by anything that compromises
it. Decline the radio permissions and every other check still works.

## The Wi-Fi watch: evil twins

A router sees every client on it; a phone sees every access point around it, the
inverse. The attack is the IP-layer twin of an IMSI catcher: a rogue access point
broadcasting a network name you trust, to lure the phone into joining so it can
read what you send. The tell is a security downgrade - a network you have only
ever joined locked (WPA2 or WPA3) offered weaker - because a real network does
not lower its own door.

The discriminator is defined by the access points you have actually joined and
their security, never by the strongest security merely scanned for a name. That
distinction is what makes it safe for common default names: a neighbour running
the same "NETGEAR" at weaker security cannot make your own connection look
downgraded, because the phone never joins the neighbour. Measured over 30 days
each for 2,000 people per naming scheme, three seeds, with mesh routers, dual-band
steering, public hotspots and colliding default names mixed in: zero false
positives whether the home name is unique or a common default.

The only alarm is being joined to a weakened copy right now (CRITICAL, caught
100% of the time), because that is the moment the twin can do harm and the moment
it becomes unambiguous. A weaker copy merely nearby is a posture note gated on a
strong signal, not an alarm, because a nearby copy is indistinguishable from a
neighbour with the same name until you connect to it. A proxy set on the Wi-Fi
link is a finding; encrypted DNS being off is a posture note. The Wi-Fi memory
persists in the app's own space and never leaves the phone.

## The satellites, seen from below

Positioning infrastructure can spoof where a phone thinks it is; the phone sees
its own satellites, and a spoofer gets things about them wrong that a real sky
never does. The tells, strongest first: a location flagged as coming from a mock
provider (software spoofing, definitive); a jump implying a speed nothing on the
ground reaches; every satellite arriving at nearly the same strong signal, where
a real sky spreads wide because satellites sit at different heights and ranges;
and the whole band collapsing while the receiver's automatic gain control drops,
which is a jammer flooding the band. The uniform-sky tell must hold across several
fixes, because a real open sky can look uniform for one instant but not for ten
seconds. Measured over 1.2 million synthetic fixes, open sky and urban canyon:
zero false positives, with all four attack shapes caught 100% of the time.

A cellular interference note rides the same idea one band over: a strong signal
carrying poor quality (SINR below zero at a strong RSRP) means something is
raising the noise floor. The common cause is a crowded area or an obstruction, so
it is a note that explains a bad connection where the bars look fine, not an
alarm.

## Catching the act

Every other check reads capability or configuration - what an app could do, or
what is set up. This one reads activity. The system reports the moment any app
starts using the microphone, camera, screen capture or location, and the watch
judges it by context: you in the app using it is you using it; an app you are not
looking at turning on the microphone while the screen is off is not. It needs the
GET_APP_OPS_STATS grant (the same one the app-operation checks use) to see other
apps, and it reads the screen-off case cleanly, because with the screen off
nothing is in the foreground - mic or camera use then, off a call, is covert and
names the app that did it. Screen capture from a background app is treated as
critical regardless, because it reads everything shown after decryption. This
turns "what could spy on you" into "something just did, here is what and when".

Two companions ride the on-demand scan: the Bluetooth devices paired to the
phone, listed so an unfamiliar one stands out, and whether USB or wireless
debugging is switched on, which is a standing way in for a computer.

## The capstone: coincidence across the watches

Each watch - cells, Wi-Fi, satellites, sensors, the clock - is tuned so that on
ordinary days it says nothing, and that near-silence is what makes this last
piece work. A targeted operation does not trip one watch; it trips several at
once, because forcing a phone onto a fake cell, spoofing its position and turning
on its microphone are things that happen together, in one place, in one minute.
Any single finding can be a coincidence. Two or three independent watches firing
inside the same few minutes is not. Because each almost never fires alone, two
coinciding by chance is vanishingly unlikely, so the coincidence alert speaks
only for the real thing, and it is the one alert in the whole app that is more
than the sum of its parts.

Fed into it, among the rest, is a clock check: the network sets the phone's time
over NITZ and the satellites carry their own, and a wide disagreement is
something a real network has no reason to cause and a fake cell does as a side
effect.

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

## Confirmed on hardware

A first run on a Pixel (484 packages, 112 of them ordinary) settled two things
that could not be established off-device.

`GET_APP_OPS_STATS` does carry the `development` flag. `adb shell pm grant`
accepts it for a normal app and `unsafeCheckOpNoThrow` then answers from the
`untrusted_app` SELinux domain. The app-operation checks are real.

`android:read_clipboard` defaults to allowed. The device reported 112 of 112
ordinary apps holding it. A rule treating that op as a granted capability would
have raised a finding against every sideloaded and hidden app on a clean phone
and returned a verdict of "strong indicators of monitoring software". The
ubiquity calibration, written because this could not be looked up, suppressed it
on the first real run.

That run also exposed a gap. `getActiveAdmins()` returns null to an ordinary app,
so device administrators could only be reported as unknown, which is where
roughly a third of monitoring products hold on to block their own uninstall. It
now enumerates every receiver advertising `DEVICE_ADMIN_ENABLED` and asks
`isAdminActive` about each by name. A positive answer is trustworthy; a negative
one is reported as probable rather than certain, because a refusal to answer and
a genuine no are indistinguishable from an ordinary app.

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
