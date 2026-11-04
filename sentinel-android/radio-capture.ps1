# radio-capture.ps1 - pull everything the shell user can reach about the radio.
#
# An app runs as untrusted_app. This runs as the shell user, which holds
# android.permission.DUMP and READ_LOGS, so it reaches things no app can:
# the telephony ring buffer, the radio log, the carrier configuration.
#
# What each phone actually exposes varies by build, so this reports which
# sources answered and which refused rather than assuming. Run it AFTER a drive,
# while the ring buffers still hold the drive.
#
# Identifiers are redacted on the way out: IMSI, IMEI, ICCID, phone numbers and
# e-mail addresses are replaced before anything is written. Check the file
# before sending it anywhere regardless.

param([string]$Phone = "")

$ErrorActionPreference = "Continue"
$out = "radio-capture-$(Get-Date -Format yyyyMMdd-HHmmss).txt"

# Connect, then find the transport that actually answers.
#
# Two things went wrong here on a real run. The address came from
# `adb mdns services`, which reports an ip:port; the phone's IP had changed, so
# every command was sent to a dead address while `adb devices` still listed the
# phone under its mDNS name and the guard reported "Connected". And the guard
# indexed a Where-Object result with [0], which on a single match indexes into
# the string and yields a char, so it threw before printing anything useful.
#
# The lesson is the one this whole project keeps relearning: being listed is not
# the same as answering. Ask, and believe the answer.

adb start-server 2>&1 | Out-Null
if ($Phone) { adb connect $Phone 2>&1 | Out-Null }
else {
  $svc = @(adb mdns services 2>$null | Select-String '_adb-tls-connect')
  if ($svc.Count -gt 0) { adb connect (($svc[0].ToString() -split '\s+')[-1]) 2>&1 | Out-Null }
}

# Take the serial from adb devices, which names the transport that exists,
# rather than from mdns, which names an address that may have moved.
$devLines = @(adb devices 2>&1 | Where-Object { $_ -match '\s+device\s*$' })
$serial = $null
foreach ($d in $devLines) {
  $cand = ($d -split '\s+')[0]
  if (-not $cand) { continue }
  # Probe it. A transport that cannot echo cannot be captured from.
  $probe = (& adb -s $cand shell echo sentinel-probe 2>&1 | Out-String)
  if ($probe -match 'sentinel-probe') { $serial = $cand; break }
}

if (-not $serial) {
  Write-Host ""
  Write-Host "  No phone answered. Nothing was captured." -ForegroundColor Red
  Write-Host ""
  Write-Host "  adb devices said:" -ForegroundColor DarkGray
  @(adb devices 2>&1) | Where-Object { $_.ToString().Trim() } | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
  Write-Host ""
  Write-Host "  A phone can be listed here and still not answer: wireless debugging"
  Write-Host "  switches itself off when the phone leaves and rejoins Wi-Fi, and its"
  Write-Host "  address changes when the router hands out a new one."
  Write-Host ""
  Write-Host "  On the phone: Settings > Developer options > Wireless debugging > on."
  Write-Host "  Then:  adb kill-server ; adb start-server ; adb connect <ip>:<port>"
  Write-Host "  If it still refuses, pair afresh with the code on that screen."
  Write-Host ""
  Write-Host "  Whatever Watch the radio recorded is a file on the phone and is not"
  Write-Host "  affected by any of this. Open Sentinel and press Radio log."
  Write-Host ""
  exit 1
}
$target = @("-s", $serial)
Write-Host ""
Write-Host "  Answering: $serial" -ForegroundColor Green

Add-Content $out "radio capture $(Get-Date -Format o)"
Add-Content $out "identifiers redacted on write; review before sending anywhere"

Write-Host "`nCollecting. Each line says whether this phone allowed it.`n"

Grab "service state ring buffer" "dumpsys telephony.registry" `
     "Holds 'local logs:' - a timestamped history of service state, cell and signal changes. The most useful single source." | Out-Null
Grab "radio log buffer" "logcat -b radio -d -t 12000" `
     "RIL traffic. On production builds this is often near-empty; on others it carries registration and SMS events an app can never see." | Out-Null
Grab "all radio-ish log lines" "logcat -d -t 4000 -s RILJ:V Telephony:V GsmCdmaPhone:V ServiceStateTracker:V NetworkRegistration:V" `
     "The same material via the main buffer, where the radio buffer is empty." | Out-Null
Grab "network registration detail" "dumpsys telephony.registry --all" `
     "Fuller registration state than the API exposes." | Out-Null
Grab "preferred network mode" "for s in 1 2 3 4 5; do settings get global preferred_network_mode\$s; done" `
     "Which technologies the phone will attach to. The 2G bit is the one that matters." | Out-Null
Grab "2G toggle" "cmd phone get-allowed-network-types-for-reason -s 0" `
     "Whether 2G is switched off. 2G does not authenticate the network, so it is what cheap interception equipment forces a phone onto." | Out-Null
Grab "carrier configuration" "dumpsys carrier_config" `
     "Includes whether the carrier permits 2G, and the roaming and emergency settings." | Out-Null
Grab "SIM and subscription" "dumpsys isub" `
     "Subscription records. Redacted heavily." | Out-Null
Grab "connectivity" "dumpsys connectivity --short" `
     "Which networks are up and which is default." | Out-Null
Grab "boot and build state" "getprop | grep -iE 'verifiedboot|flash.locked|build.tags|debuggable|secure'" `
     "Whether the system partition is still the manufacturer's." | Out-Null
Grab "bluetooth event history" "dumpsys bluetooth_manager" `
     "Android's own record of Bluetooth connections and disconnections, for lining a dropped car link up against what the radio did." | Out-Null
Grab "A2DP audio stream events" "logcat -d -t 20000 -s btif_av:V bta_av:V A2dpService:V BluetoothA2dpService:V BluetoothA2dpServiceJni:V bt_stack:V AudioFlinger:V" `
     "The audio stream layer. A brief dropout that recovers mutes the A2DP stream without tearing down the link, so it leaves no trace in the connection log and shows up only here." | Out-Null
Grab "bluetooth HCI snoop log present?" "ls -l /data/misc/bluetooth/logs/ 2>/dev/null" `
     "Every HCI packet, if Developer options > Enable Bluetooth HCI snoop log is on. The definitive record; needs Wireshark to read." | Out-Null
Grab "current network type" "dumpsys telephony.registry | grep -iE 'mDataRegState|mChannelNumber|getRilDataRadioTechnology|NrState' | head -20" `
     "Which technology and channel the phone is on right now, for confirming an LTE-only test took effect." | Out-Null
Grab "Sentinel radio events" "cat /sdcard/Android/data/com.bluetier.sentinel/files/events.log" `
     "Anything the watch thought worth saying, if it was running." | Out-Null
Grab "Sentinel radio log" "cat /sdcard/Android/data/com.bluetier.sentinel/files/radio.log" `
     "The six-second samples, for lining a Bluetooth drop up against a band change." | Out-Null

Write-Host "`nWrote $out"
Write-Host "Identifiers were redacted on write. Read it before sending it anywhere.`n"
