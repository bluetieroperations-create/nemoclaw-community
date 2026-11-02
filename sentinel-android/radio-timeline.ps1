# radio-timeline.ps1 - turn a radio capture into a readable timeline.
#
# The formats here were taken from a real capture and the patterns were checked
# against its actual lines, including two that must NOT match: the first version
# of the summariser read mipc_msg_get_msg_type(02) as a silent SMS 1325 times,
# and RfxController.onCheckIfRejectMessage as a network refusal.
#
# Two sources carry the history:
#   dumpsys telephony.registry  'local logs:'  - timestamped service state, with
#                                 mChannelNumber, which is the ARFCN
#   logcat -b radio  RmmNwHdlr   - serving cell: plmn, arfcn, cid, tac, rsrp,
#                                 rsrq, sinr and timing advance
#
# Neither is reachable by an app. Both are reachable by the shell user.

param([string]$Path = "")

if (-not $Path) {
  $Path = (Get-ChildItem radio-capture-*.txt | Sort-Object LastWriteTime -Desc | Select-Object -First 1).FullName
}
if (-not (Test-Path $Path)) { Write-Host "No capture file found." -ForegroundColor Red; exit 1 }

$raw = Get-Content $Path -Raw
$out = "radio-timeline-$(Get-Date -Format yyyyMMdd-HHmmss).txt"
function W($s) { Add-Content $out $s; }

# ---------- frequency -> band -------------------------------------------------
function NrMhz([double]$n) {
  if ($n -lt 600000)  { return $n * 0.005 }
  if ($n -lt 2016667) { return 3000 + ($n - 600000) * 0.015 }
  return 24250.08 + ($n - 2016667) * 0.060
}
# Downlink ranges. Incomplete on purpose: a frequency outside them is reported
# as "not in my table", never as an anomaly, because a gap in this list is my
# ignorance and not something wrong with the network.
$BANDS = @(
  @(617,652,'n71'), @(717,728,'n29'), @(729,746,'n12'), @(746,756,'n13'),
  @(758,768,'n14'), @(859,894,'n5/n26'), @(925,960,'n8'), @(1805,1880,'n3'),
  @(1930,1995,'n2/n25'), @(2110,2200,'n66/n4/n1'), @(2350,2360,'n30'),
  @(2496,2690,'n41'), @(3300,3800,'n77/n78'), @(24250,29500,'n261 mmWave')
)
function BandOf([double]$mhz) {
  foreach ($b in $BANDS) { if ($mhz -ge $b[0] -and $mhz -le $b[1]) { return $b[2] } }
  return "not in my table"
}
# Bluetooth and 2.4GHz Wi-Fi sit at 2400-2483.5 MHz.
function Crowds24([double]$mhz) { return ($mhz -ge 2300 -and $mhz -le 2700) }

# ---------- parse -------------------------------------------------------------
$rmm = [regex]'RmmNwHdlr:\s*\[(?<slot>\d)\]\s*(?<rat>\w+):state\s*=\s*(?<state>\d+),\s*registred=(?<reg>\d+),\s*plmn\s*=\s*(?<plmn>\S+?),\s*pscPci\s*=\s*(?<pci>\S+),\s*(?:nr_)?arfcn\s*=\s*(?<arfcn>\d+),\s*cid\s*=\s*(?<cid>\S+),\s*tac\s*=\s*(?<tac>\S+),\s*rsrp\s*=\s*(?<rsrp>-?\d+),\s*rsrq\s*=\s*(?<rsrq>-?\d+),\s*sinr\s*=\s*(?<sinr>-?\d+)'
$ring = [regex]'^(?<ts>\d{4}-\d\d-\d\dT[\d:.]+)\s+-\s+(?<what>\w+)'
$chan = [regex]'mChannelNumber=(?<n>-?\d+)'
$dreg = [regex]'mDataRegState=(?<v>\d)\((?<name>\w+)\)'
$endc = [regex]'endc state currRat:\s*(?<rat>\d+)\s*\(Endc:\s*(?<endc>true|false)\)'

W "radio timeline  $(Get-Date -Format o)"
W "source: $(Split-Path $Path -Leaf)"
W ""

# --- serving cell history from the modem log ---
W "===== serving cell, from the modem log ====="
W "(rsrp/rsrq/sinr are 3GPP indices; dBm = rsrp - 156, dB = rsrq/2 - 43, sinr/2 - 23)"
W ""
$seenCell = @{}
$lastBand = $null
foreach ($line in ($raw -split "`r?`n")) {
  $m = $rmm.Match($line)
  if (-not $m.Success) { continue }
  $n = [double]$m.Groups['arfcn'].Value
  $mhz = NrMhz $n
  $band = BandOf $mhz
  $dbm = [int]$m.Groups['rsrp'].Value - 156
  $key = "$($m.Groups['cid'].Value)|$($m.Groups['tac'].Value)|$band"
  if (-not $seenCell.ContainsKey($key)) {
    $seenCell[$key] = 0
    $flag = if (Crowds24 $mhz) { "  <- crowds Bluetooth" } else { "" }
    W ("  {0,-8} arfcn {1,-8} {2,9:N2} MHz  {3,-14} tac={4,-8} pci={5,-6} rsrp={6} dBm{7}" -f `
       $m.Groups['rat'].Value, $n, $mhz, $band, $m.Groups['tac'].Value, $m.Groups['pci'].Value, $dbm, $flag)
  }
  $seenCell[$key]++
}
if ($seenCell.Count -eq 0) { W "  (no RmmNwHdlr lines - this build may name the modem log differently)" }

# --- the ring buffer: the actual timeline ---
W ""
W "===== timeline, from the telephony ring buffer ====="
$events = New-Object System.Collections.ArrayList
foreach ($line in ($raw -split "`r?`n")) {
  $r = $ring.Match($line)
  if (-not $r.Success) { continue }
  $c = $chan.Match($line); $g = $dreg.Match($line)
  $o = [PSCustomObject]@{
    Time = $r.Groups['ts'].Value
    What = $r.Groups['what'].Value
    Chan = if ($c.Success) { [double]$c.Groups['n'].Value } else { $null }
    Reg  = if ($g.Success) { $g.Groups['name'].Value } else { $null }
  }
  [void]$events.Add($o)
}
W ("  $($events.Count) entries")
W ""
$prevBand = $null; $prevReg = $null; $shown = 0
foreach ($e in $events) {
  $note = $null
  if ($e.Chan -ne $null -and $e.Chan -gt 0) {
    $mhz = NrMhz $e.Chan; $b = BandOf $mhz
    if ($b -ne $prevBand) {
      $flag = if (Crowds24 $mhz) { "   <- crowds Bluetooth" } else { "" }
      $note = ("band now {0} ({1:N2} MHz){2}" -f $b, $mhz, $flag)
      $prevBand = $b
    }
  }
  if ($e.Reg -and $e.Reg -ne $prevReg) {
    $note = (($note, "data $($e.Reg)") | Where-Object { $_ }) -join "; "
    $prevReg = $e.Reg
  }
  if ($note) { W ("  {0}  {1}" -f $e.Time, $note); $shown++ }
}
if ($shown -eq 0) { W "  (no band or service-state changes in the buffer - it may be shorter than the drive)" }

# --- bluetooth, if the capture has it ---
if ($raw -match 'bluetooth event history') {
  W ""
  W "===== bluetooth ====="
  $bt = ($raw -split "`r?`n" | Where-Object { $_ -match '(?i)(ACL |bond|connect|disconnect).*(\d\d:\d\d:\d\d)' } | Select-Object -First 25)
  if ($bt) { $bt | ForEach-Object { W ("  " + $_.Trim()) } }
  else { W "  (no timestamped connection lines found)" }
}

W ""
W "===== how to read this ====="
W "A band marked 'crowds Bluetooth' sits between 2300 and 2700 MHz, against the"
W "2400-2483 MHz range Bluetooth uses. A phone shares one front end between them"
W "and quiets the short-range radio to protect the cellular link, so a car audio"
W "drop within seconds of such a band change is the phone working as designed."
W ""
W "What would NOT be that: a drop to LTE, UMTS or GSM while the signal stays"
W "strong, a tracking area that changes without the cell changing, or a cell that"
W "has no neighbours at close range. None of those are in this file; they are"
W "what the Watch the radio mode looks for while you drive."

Write-Host "Wrote $out" -ForegroundColor Green
Get-Content $out | Select-Object -First 40
