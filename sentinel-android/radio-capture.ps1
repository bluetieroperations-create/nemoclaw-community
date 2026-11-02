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

if (-not $Phone) {
  $line = adb mdns services 2>$null | Select-String '_adb-tls-connect' | Select-Object -First 1
  if ($line) { $Phone = ($line.ToString() -split '\s+')[-1] }
}
if ($Phone) { adb connect $Phone 2>$null | Out-Null }
$target = if ($Phone) { @("-s", $Phone) } else { @() }

function Redact([string]$t) {
  if (-not $t) { return "" }
  $t = $t -replace '\b\d{14,16}\b', '<identifier-removed>'      # IMEI / IMSI / ICCID
  $t = $t -replace '(?<![\d.])\+?1?[-. (]?\d{3}[-. )]\s?\d{3}[-. ]\d{4}\b', '<phone-removed>'
  $t = $t -replace '[\w.+-]+@[\w-]+\.[\w.]+', '<email-removed>'
  $t = $t -replace '(?i)(imsi|iccid|msisdn|subscriberId|deviceId)([=:\s]+)\S+', '$1$2<removed>'
  return $t
}

function Grab($label, $cmd, $note) {
  Write-Host -NoNewline ("  {0,-34}" -f $label)
  $text = (& adb @target shell $cmd 2>&1 | Out-String)
  $ok = $LASTEXITCODE -eq 0 -and $text.Trim().Length -gt 40 `
        -and $text -notmatch 'Permission Denial|SecurityException|Unknown command|not found'
  if ($ok) { Write-Host "ok ($($text.Length) chars)" -ForegroundColor Green }
  else     { Write-Host "refused or empty" -ForegroundColor DarkYellow }
  Add-Content $out "`n===== $label ====="
  Add-Content $out "# $note"
  Add-Content $out "# command: adb shell $cmd"
  if ($ok) { Add-Content $out (Redact $text) }
  else     { Add-Content $out "(refused or empty - this phone's build does not expose it to the shell user)" }
  return $ok
}

Add-Content $out "radio capture $(Get-Date -Format o)"
Add-Content $out "identifiers redacted on write; review before sending anywhere"

Write-Host "`nCollecting. Each line says whether this phone allowed it.`n"

Grab "service state ring buffer" "dumpsys telephony.registry" `
     "Holds 'local logs:' - a timestamped history of service state, cell and signal changes. The most useful single source." | Out-Null
Grab "radio log buffer" "logcat -b radio -d -t 3000" `
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
Grab "modem network handler log" "logcat -b radio -d -t 4000 -s RmmNwHdlr:V NetworkTypeController:V RILJ:V" `
     "The serving-cell lines: plmn, nr_arfcn, cid, tac, rsrp and timing advance, which no app API exposes." | Out-Null
Grab "Sentinel radio events" "cat /sdcard/Android/data/com.bluetier.sentinel/files/events.log" `
     "Anything the watch thought worth saying, if it was running." | Out-Null
Grab "Sentinel radio log" "cat /sdcard/Android/data/com.bluetier.sentinel/files/radio.log" `
     "The six-second samples, for lining a Bluetooth drop up against a band change." | Out-Null

Write-Host "`nWrote $out"
Write-Host "Identifiers were redacted on write. Read it before sending it anywhere.`n"
