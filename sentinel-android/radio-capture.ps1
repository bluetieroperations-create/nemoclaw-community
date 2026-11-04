# radio-capture.ps1 - pull everything the shell user can reach about the radio.
#
# An app runs as untrusted_app. This runs as the shell user, which holds
# android.permission.DUMP and READ_LOGS, so it reaches the telephony ring
# buffer, the radio log, the Bluetooth connection history and the carrier
# configuration - none of which any app can read.
#
# What a given build exposes varies, so each source reports whether it answered
# rather than being assumed. Run it soon after driving: the telephony ring
# buffer holds only the last few hours.
#
# Identifiers are redacted on write. Read the file before sending it anywhere.

param([string]$Phone = "")

$ErrorActionPreference = "Continue"
$out = "radio-capture-$(Get-Date -Format yyyyMMdd-HHmmss).txt"

# ---------------------------------------------------------------- connection
#
# Being listed is not the same as answering. `adb mdns services` reports an
# ip:port that goes stale the moment the router hands out a new address, while
# `adb devices` still lists the phone under its mDNS name. Taking the first on
# trust sent sixteen commands to a dead address and reported them as refusals.
# So: take candidates from adb devices, then ask each one to echo.

adb start-server 2>&1 | Out-Null
if ($Phone) {
  adb connect $Phone 2>&1 | Out-Null
} else {
  $svc = @(adb mdns services 2>$null | Select-String '_adb-tls-connect')
  if ($svc.Count -gt 0) { adb connect (($svc[0].ToString() -split '\s+')[-1]) 2>&1 | Out-Null }
}

$serial = $null
foreach ($d in @(adb devices 2>&1 | Where-Object { $_ -match '\s+device\s*$' })) {
  $cand = ($d.ToString() -split '\s+')[0]
  if (-not $cand) { continue }
  $probe = (& adb -s $cand shell echo sentinel-probe 2>&1 | Out-String)
  if ($probe -match 'sentinel-probe') { $serial = $cand; break }
}

if (-not $serial) {
  Write-Host ""
  Write-Host "  No phone answered. Nothing was captured." -ForegroundColor Red
  Write-Host ""
  Write-Host "  adb devices said:" -ForegroundColor DarkGray
  @(adb devices 2>&1) | Where-Object { $_.ToString().Trim() } |
    ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
  Write-Host ""
  Write-Host "  Wireless debugging switches itself off when the phone leaves and"
  Write-Host "  rejoins Wi-Fi, and the address changes when the router reassigns it."
  Write-Host "  On the phone: Settings > Developer options > Wireless debugging > on."
  Write-Host "  Then: adb kill-server ; adb start-server ; adb devices"
  Write-Host ""
  Write-Host "  Whatever Watch the radio recorded is a file on the phone and is not"
  Write-Host "  affected by any of this. Open Sentinel and press Radio log."
  Write-Host ""
  exit 1
}
$target = @("-s", $serial)
Write-Host ""
Write-Host "  Answering: $serial" -ForegroundColor Green

# ------------------------------------------------------------------ helpers

function Redact([string]$t) {
  if (-not $t) { return "" }
  $t = $t -replace '\b\d{14,16}\b', '<identifier-removed>'
  $t = $t -replace '(?<![\d.])\+?1?[-. (]?\d{3}[-. )]\s?\d{3}[-. ]\d{4}\b', '<phone-removed>'
  $t = $t -replace '[\w.+-]+@[\w-]+\.[\w.]+', '<email-removed>'
  $t = $t -replace '(?i)(imsi|iccid|msisdn|subscriberId|deviceId)([=:\s]+)\S+', '$1$2<removed>'
  return $t
}

function Grab($label, $cmd, $note) {
  Write-Host -NoNewline ("  {0,-34}" -f $label)
  $text = (& adb @target shell $cmd 2>&1 | Out-String)
  $ok = $text.Trim().Length -gt 40 `
        -and $text -notmatch 'Permission Denial|SecurityException|Unknown command|not found|device .* not found'
  if ($ok) { Write-Host "ok ($($text.Length) chars)" -ForegroundColor Green }
  else     { Write-Host "refused or empty" -ForegroundColor DarkYellow }
  Add-Content $out "`n===== $label ====="
  Add-Content $out "# $note"
  Add-Content $out "# command: adb shell $cmd"
  if ($ok) { Add-Content $out (Redact $text) }
  else     { Add-Content $out "(refused or empty on this build)" }
}

# -------------------------------------------------------------------- collect

Add-Content $out "radio capture $(Get-Date -Format o)"
Add-Content $out "device: $serial"
Add-Content $out "identifiers redacted on write; review before sending anywhere"

Write-Host ""
Write-Host "Collecting. Each line says whether this phone allowed it."
Write-Host ""

Grab "service state ring buffer" "dumpsys telephony.registry" `
     "Holds 'local logs:', a timestamped history of service state with mChannelNumber, which is the ARFCN. The most useful single source."
Grab "radio log buffer" "logcat -b radio -d -t 12000" `
     "RIL traffic, including RmmNwHdlr serving-cell lines with plmn, arfcn, cid, tac, rsrp and timing advance."
Grab "network registration detail" "dumpsys telephony.registry --all" `
     "Fuller registration state than the public API exposes."
Grab "preferred network mode" "settings get global preferred_network_mode" `
     "Which technologies the phone will attach to."
Grab "carrier configuration" "dumpsys carrier_config" `
     "Includes whether the carrier permits 2G, and roaming and emergency settings."
Grab "SIM and subscription" "dumpsys isub" `
     "Subscription records. Redacted heavily."
Grab "connectivity" "dumpsys connectivity --short" `
     "Which networks are up and which is default."
Grab "boot and build state" "getprop | grep -iE 'verifiedboot|flash.locked|build.tags|debuggable'" `
     "Whether the system partition is still the manufacturer's."
Grab "bluetooth event history" "dumpsys bluetooth_manager" `
     "Android's record of Bluetooth connections, with teardown times and disconnect reasons."
Grab "A2DP audio stream events" "logcat -d -t 20000 -s btif_av:V bta_av:V A2dpService:V BluetoothA2dpService:V AudioFlinger:V" `
     "The audio stream layer. A brief dropout that recovers mutes A2DP without tearing down the link, so it shows up only here."
Grab "bluetooth HCI snoop log" "ls -l /data/misc/bluetooth/logs/ 2>/dev/null" `
     "Every HCI packet, if Developer options > Enable Bluetooth HCI snoop log is on."
Grab "current network type" "dumpsys telephony.registry | grep -iE 'mDataRegState|mChannelNumber' | head -20" `
     "Which technology and channel the phone is on now, for confirming an LTE-only test took effect."
# --- wireless Android Auto rides a Wi-Fi Direct link, not Bluetooth ---------
# The Bluetooth link to the head unit carries only hands-free calling. Screen,
# touch and all audio go over Wi-Fi Direct, so a dropout that kills the whole
# infotainment system is a Wi-Fi event and appears in none of the Bluetooth
# sources above.
Grab "wifi direct state" "dumpsys wifip2p" `
     "The Wi-Fi Direct link wireless Android Auto runs on, including the group, the channel and recent failures."
Grab "wifi state and history" "dumpsys wifi" `
     "Connection history, scan activity and roaming. A scan can interrupt a Direct link."
Grab "wifi connection events" "logcat -d -t 20000 -s WifiP2pService:V WifiP2pNative:V wpa_supplicant:V WifiClientModeImpl:V ClientModeImpl:V WifiStateMachine:V WifiNative:V" `
     "Where a Wi-Fi Direct link going down is recorded, with a reason."
Grab "android auto events" "logcat -d -t 20000 -s CAR.SERVICE:V CAR.WIFI:V CAR.GAL:V GH.WirelessClient:V GH.CarClientManager:V GH.LifetimeManager:V" `
     "Android Auto's own view of the projection session starting, stopping and failing."
Grab "android auto process" "dumpsys activity services com.google.android.projection.gearhead | head -60" `
     "Whether the projection service is running and what it is bound to."

Grab "Sentinel radio events" "cat /sdcard/Android/data/com.bluetier.sentinel/files/events.log" `
     "What the watch thought worth saying, including timestamped audio dropouts."
Grab "Sentinel radio log" "cat /sdcard/Android/data/com.bluetier.sentinel/files/radio.log" `
     "The six-second samples, for lining a Bluetooth drop up against a band change."

Write-Host ""
Write-Host "Wrote $out"
Write-Host "Identifiers were redacted on write. Read it before sending it anywhere."
Write-Host ""
