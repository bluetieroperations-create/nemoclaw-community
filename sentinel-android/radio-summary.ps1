# radio-summary.ps1 - read a radio-capture file and write a small shareable summary.
#
# The capture runs to a megabyte and holds subscriber material. This reads it
# where it sits and writes a short description of its SHAPE: which log tags
# appear and how often, a handful of sample lines per tag, and counts for the
# things worth hunting. That is enough to write a parser against without the
# capture itself going anywhere.
#
# Everything written here is redacted again on the way out.

param([string]$Path = "")

if (-not $Path) {
  $Path = (Get-ChildItem radio-capture-*.txt | Sort-Object LastWriteTime -Desc | Select-Object -First 1).FullName
}
if (-not (Test-Path $Path)) { Write-Host "No capture file found." -ForegroundColor Red; exit 1 }

$out = "radio-summary-$(Get-Date -Format yyyyMMdd-HHmmss).txt"
$raw = Get-Content $Path -Raw
Write-Host "Reading $Path ($([math]::Round($raw.Length/1KB)) KB)`n"

function Redact([string]$t) {
  if (-not $t) { return "" }
  $t = $t -replace '\b\d{9,20}\b', '<num>'
  $t = $t -replace '[\w.+-]+@[\w-]+\.[\w.]+', '<email>'
  $t = $t -replace '(?i)(imsi|iccid|msisdn|subscriber\w*|deviceId|phoneNumber)([=:\s"]+)\S+', '$1$2<removed>'
  $t = $t -replace '\b[0-9A-Fa-f]{16,}\b', '<hex>'
  return $t
}
function W($s) { Add-Content $out $s }

# split the capture back into its sections
$sections = @{}
$current = $null; $buf = New-Object System.Collections.ArrayList
foreach ($line in ($raw -split "`r?`n")) {
  if ($line -match '^===== (.+) =====$') {
    if ($current) { $sections[$current] = $buf -join "`n" }
    $current = $Matches[1]; $buf = New-Object System.Collections.ArrayList
  } elseif ($current) { [void]$buf.Add($line) }
}
if ($current) { $sections[$current] = $buf -join "`n" }

W "radio capture summary  $(Get-Date -Format o)"
W "source: $(Split-Path $Path -Leaf)  ($([math]::Round($raw.Length/1KB)) KB)"
W ""
W "===== sections ====="
foreach ($k in $sections.Keys | Sort-Object) {
  $n = ($sections[$k] -split "`n").Count
  W ("  {0,-32} {1,7} lines" -f $k, $n)
}

# ---- the radio log: what tags does this build actually emit? --------------
$radio = $sections['radio log buffer']
if ($radio -and $radio.Length -gt 200) {
  W ""
  W "===== radio log: tags present ====="
  $tags = @{}
  foreach ($line in ($radio -split "`n")) {
    # logcat default: MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message
    if ($line -match '^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+(\w)\s+([^:]+):') {
      $t = $Matches[2].Trim()
      if ($tags.ContainsKey($t)) { $tags[$t]++ } else { $tags[$t] = 1 }
    }
  }
  if ($tags.Count -eq 0) {
    W "  (no line matched the expected logcat shape - first 12 raw lines follow)"
    ($radio -split "`n" | Select-Object -First 12) | ForEach-Object { W ("  | " + (Redact $_)) }
  } else {
    foreach ($t in ($tags.GetEnumerator() | Sort-Object Value -Desc | Select-Object -First 40)) {
      W ("  {0,-34} {1,7}" -f $t.Key, $t.Value)
    }
    W ""
    W "===== radio log: three sample lines per busiest tag ====="
    foreach ($t in ($tags.GetEnumerator() | Sort-Object Value -Desc | Select-Object -First 12)) {
      W ""
      W ("--- " + $t.Key + " ---")
      $pat = [regex]::Escape($t.Key) + ':'
      ($radio -split "`n" | Where-Object { $_ -match $pat } | Select-Object -First 3) |
        ForEach-Object { W ("  " + (Redact $_.Trim()).Substring(0, [Math]::Min(190, (Redact $_.Trim()).Length))) }
    }
  }

  W ""
  W "===== what is worth hunting for, and whether it is there ====="
  # Each pattern must be anchored to something that only means the thing.
  # The first version used 'type.?0', which matched mipc_msg_get_msg_type(02) in
  # modem IPC logging 1325 times and reported it as silent SMS, and 'reject',
  # which matched RfxController.onCheckIfRejectMessage and reported a framework
  # check as a network refusal. A loose pattern here produces exactly the kind of
  # confident wrong answer this whole tool exists to avoid.
  $hunt = [ordered]@{
    'SMS delivery'             = '(?i)\b(NEW_SMS|SMS_DELIVER|onNewSms|dispatchMessage|InboundSmsHandler|SmsDispatcher)\b'
    'silent / type-0 SMS'      = '(?i)(silent sms|type ?zero|\bTYPE0\b|SMS_TYPE_0|class ?0 sms|\bMT_SMS\b|protocolIdentifier *= *0x40)'
    'binary / data SMS'        = '(?i)(data ?sms|binary ?sms|port ?addressed|destPort|\bWAP_PUSH\b|SIM ?Toolkit|\bCAT_|\bSTK_)'
    'paging'                   = '(?i)(\bpaging\b|PAGING_|\bpage ?record\b)'
    'attach / registration'    = '(?i)(\battach\b|registred|mVoiceRegState|mDataRegState|NetworkRegistrationInfo|RRC_CONNECT)'
    'registration refused'     = '(?i)(REGISTRATION_DENIED|registration ?reject|attach ?reject|\bcause ?= ?\d+|DENIED)'
    'identity requested'       = '(?i)(identity ?request|IMSI ?request|GET_IMSI|REQUEST_IDENTITY)'
    'ciphering / security'     = '(?i)(ciphering|SECURITY_MODE|NAS_SEC|integrity ?protect|\bEEA[0-3]\b|\bNEA[0-3]\b|\bA5/[0-3]\b)'
    'cell reselect / handover' = '(?i)(reselect|\bhandover\b|HANDOVER|CELL_CHANGE|mobility)'
    'serving cell detail'      = '(?i)(nr_arfcn|earfcn|nrarfcn|uarfcn|mChannelNumber)'
    'tracking area'            = '(?i)(\btac ?=|tracking ?area|\blac ?=)'
    'timing advance'           = '(?i)(\bta ?=|timing ?advance|mTimingAdvance)'
  }
  foreach ($h in $hunt.GetEnumerator()) {
    $m = ([regex]::Matches($radio, $h.Value)).Count
    $mark = if ($m -gt 0) { "yes" } else { " - " }
    W ("  {0,-26} {1,4}  {2,6} hits" -f $h.Key, $mark, $m)
  }

  W ""
  W "===== sample lines for the hunted items that appeared ====="
  foreach ($h in $hunt.GetEnumerator()) {
    $hits = ($radio -split "`n" | Where-Object { $_ -match $h.Value } | Select-Object -First 2)
    if ($hits) {
      W ""
      W ("--- " + $h.Key + " ---")
      $hits | ForEach-Object {
        $r = Redact $_.Trim()
        W ("  " + $r.Substring(0, [Math]::Min(190, $r.Length)))
      }
    }
  }
}

# ---- the telephony ring buffer -------------------------------------------
$reg = $sections['service state ring buffer']
if ($reg -and $reg.Length -gt 200) {
  W ""
  W "===== telephony registry: the local log ring buffers ====="
  $lines = $reg -split "`n"
  $marks = @()
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '(?i)local log|Local Logs|log:') { $marks += $i }
  }
  W ("  ring buffer markers found: " + $marks.Count)
  foreach ($m in ($marks | Select-Object -First 4)) {
    W ""
    W ("--- around line " + $m + ": " + (Redact $lines[$m].Trim()) + " ---")
    for ($j = $m + 1; $j -lt [Math]::Min($m + 13, $lines.Count); $j++) {
      $r = Redact $lines[$j].Trim()
      if ($r) { W ("  " + $r.Substring(0, [Math]::Min(180, $r.Length))) }
    }
  }
}

Write-Host "Wrote $out  ($([math]::Round((Get-Item $out).Length/1KB)) KB)" -ForegroundColor Green
Write-Host "It is a description of the capture's shape, re-redacted. Read it, then send it.`n"
