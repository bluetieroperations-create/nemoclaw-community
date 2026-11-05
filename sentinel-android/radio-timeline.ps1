# radio-timeline.ps1 - turn a radio capture into a timeline, and line the
# Bluetooth drops up against what the radio was doing.
#
# Formats taken from real captures. Two bugs are pinned here by their fixes:
#   - the ring-buffer pattern was anchored with ^, but dumpsys indents its
#     entries, so it matched almost nothing. It now allows leading whitespace.
#   - the Bluetooth section matched any line with "connect" and a clock time,
#     which led with NetworkAgentInfo noise. It now parses shim::acl only.
#
# Nothing here is reachable by an app. All of it is reachable by the shell user.

param([string]$Path = "")
if (-not $Path) {
  $Path = (Get-ChildItem radio-capture-*.txt | Sort-Object LastWriteTime -Desc | Select-Object -First 1).FullName
}
if (-not (Test-Path $Path)) { Write-Host "No capture file found." -ForegroundColor Red; exit 1 }

$lines = (Get-Content $Path -Raw) -split "`r?`n"
$out = "radio-timeline-$(Get-Date -Format yyyyMMdd-HHmmss).txt"
function W($s) { Add-Content $out $s }

function NrMhz([double]$n) {
  if ($n -lt 600000)  { return $n * 0.005 }
  if ($n -lt 2016667) { return 3000 + ($n - 600000) * 0.015 }
  return 24250.08 + ($n - 2016667) * 0.060
}
$BANDS = @(
  @(617,652,'n71'), @(717,728,'n29'), @(729,746,'n12'), @(746,756,'n13'),
  @(758,768,'n14'), @(859,894,'n5/n26'), @(925,960,'n8'), @(1805,1880,'n3'),
  @(1930,1995,'n2/n25'), @(2110,2200,'n66/n4/n1'), @(2350,2360,'n30'),
  @(2496,2690,'n41'), @(3300,3800,'n77/n78'), @(24250,29500,'n261 mmWave')
)
function BandOf([double]$mhz) {
  foreach ($b in $BANDS) { if ($mhz -ge $b[0] -and $mhz -le $b[1]) { return $b[2] } }
  return "not in my table"   # a gap here is my ignorance, never an anomaly
}
function Crowds24([double]$mhz) { return ($mhz -ge 2300 -and $mhz -le 2700) }

# ---------------- parse ------------------------------------------------------
# leading \s* matters: dumpsys indents these
$ring = [regex]'^\s*(?<ts>\d{4}-\d\d-\d\dT[\d:.]+)\s+-\s+(?<what>\w+)'
$chan = [regex]'mChannelNumber=(?<n>-?\d+)'
$dreg = [regex]'mDataRegState=(?<v>\d)\((?<name>\w+)\)'
$rmm  = [regex]'RmmNwHdlr:\s*\[(?<slot>\d)\]\s*(?<rat>\w+):state\s*=\s*\d+,\s*registred=\d+,\s*plmn\s*=\s*(?<plmn>\S+?),\s*pscPci\s*=\s*(?<pci>\S+),\s*(?:nr_)?arfcn\s*=\s*(?<arfcn>\d+),\s*cid\s*=\s*(?<cid>\S+),\s*tac\s*=\s*(?<tac>\S+),\s*rsrp\s*=\s*(?<rsrp>-?\d+)'
$acl  = [regex]'shim::acl\s+peer:(?<peer>[0-9a-fx:]+)(?:\[(?<kind>\w+))?.*?is_locally_initiated:(?<local>\w+)\s+creation_time:(?<start>[\d-]+ [\d:.]+)\s+teardown_time:(?<end>[\d-]+ [\d:.]+)\s+disconnect_reason:(?<reason>\w+)'

$radioState = New-Object System.Collections.ArrayList   # time-ordered band history
$sessions   = New-Object System.Collections.ArrayList
$cells      = @{}

foreach ($line in $lines) {
  $r = $ring.Match($line)
  if ($r.Success) {
    $c = $chan.Match($line); $g = $dreg.Match($line)
    if ($c.Success -and [double]$c.Groups['n'].Value -gt 0) {
      $n = [double]$c.Groups['n'].Value; $mhz = NrMhz $n
      [void]$radioState.Add([PSCustomObject]@{
        Time = [datetime]::Parse($r.Groups['ts'].Value)
        Mhz = $mhz; Band = (BandOf $mhz); Reg = $(if ($g.Success) { $g.Groups['name'].Value } else { $null })
      })
    }
    continue
  }
  $m = $rmm.Match($line)
  if ($m.Success) {
    $n = [double]$m.Groups['arfcn'].Value; $mhz = NrMhz $n
    $k = "$($m.Groups['cid'].Value)|$($m.Groups['tac'].Value)|$(BandOf $mhz)"
    if (-not $cells.ContainsKey($k)) {
      $cells[$k] = [PSCustomObject]@{
        Rat=$m.Groups['rat'].Value; Arfcn=$n; Mhz=$mhz; Band=(BandOf $mhz)
        Tac=$m.Groups['tac'].Value; Pci=$m.Groups['pci'].Value
        Dbm=([int]$m.Groups['rsrp'].Value - 156); N=0 }
    }
    $cells[$k].N++
    continue
  }
  $a = $acl.Match($line)
  if ($a.Success) {
    $s = [datetime]::Parse($a.Groups['start'].Value)
    $e = [datetime]::Parse($a.Groups['end'].Value)
    [void]$sessions.Add([PSCustomObject]@{
      Peer=$a.Groups['peer'].Value
      Kind=$(if ($a.Groups['kind'].Success) { $a.Groups['kind'].Value } else { 'CLASSIC' })
      Start=$s; End=$e; Secs=[math]::Round(($e-$s).TotalSeconds,1)
      Local=($a.Groups['local'].Value -eq 'true'); Reason=$a.Groups['reason'].Value })
  }
}

W "radio timeline  $(Get-Date -Format o)"
W "source: $(Split-Path $Path -Leaf)"

# ---------------- serving cells ---------------------------------------------
W ""
W "===== serving cells seen in the modem log ====="
if ($cells.Count -eq 0) {
  W "  none in this capture. RmmNwHdlr only logs when the cell or registration"
  W "  changes, so a phone sitting still on Wi-Fi writes nothing. Drive, then capture."
} else {
  W "  (rsrp dBm = index - 156)"
  foreach ($c in ($cells.Values | Sort-Object -Property N -Desc)) {
    $flag = if (Crowds24 $c.Mhz) { "  <- crowds Bluetooth" } else { "" }
    W ("  {0,-5} arfcn {1,-8} {2,9:N2} MHz  {3,-14} tac={4,-8} pci={5,-6} rsrp={6,5} dBm  x{7}{8}" -f `
       $c.Rat, $c.Arfcn, $c.Mhz, $c.Band, $c.Tac, $c.Pci, $c.Dbm, $c.N, $flag)
  }
}

# ---------------- band timeline ----------------------------------------------
W ""
W "===== band changes, from the telephony ring buffer ====="
$sorted = $radioState | Sort-Object Time
W ("  $($sorted.Count) state entries" + $(if ($sorted.Count) { "  spanning $($sorted[0].Time.ToString('MM-dd HH:mm')) to $($sorted[-1].Time.ToString('MM-dd HH:mm'))" } else { "" }))
W ""
$prev = $null; $changes = 0
foreach ($e in $sorted) {
  if ($e.Band -ne $prev) {
    $flag = if (Crowds24 $e.Mhz) { "   <- crowds Bluetooth" } else { "" }
    W ("  {0}  {1,-14} {2,9:N2} MHz{3}" -f $e.Time.ToString('MM-dd HH:mm:ss'), $e.Band, $e.Mhz, $flag)
    $prev = $e.Band; $changes++
  }
}
if ($changes -eq 0) { W "  (no band changes recorded in this buffer)" }

# ---------------- bluetooth --------------------------------------------------
W ""
W "===== bluetooth links, by device ====="
if ($sessions.Count -eq 0) { W "  (no shim::acl lines found)" }
else {
  foreach ($grp in ($sessions | Group-Object Peer | Sort-Object Count -Desc)) {
    $g = $grp.Group
    $med = ($g | Sort-Object Secs)[[int]($g.Count/2)].Secs
    $reasons = ($g | Group-Object Reason | Sort-Object Count -Desc | ForEach-Object { "$($_.Name) x$($_.Count)" }) -join ", "
    W ""
    W ("  {0}  [{1}]  {2} session(s), median {3}s" -f $grp.Name, $g[0].Kind, $g.Count, $med)
    W ("    {0}" -f $reasons)
  }
  W ""
  W "  Reasons, and what they mean for this question:"
  W "    CONNECTION_TIMEOUT            the link failed - packets stopped arriving."
  W "                                  This is what radio interference looks like."
  W "    CONNECTION_TERMINATED_BY_LOCAL_HOST   your phone chose to close it."
  W "    REMOTE_USER_TERMINATED_CONNECTION     the other device closed it."
  W "    TRANSACTION_RESPONSE_TIMEOUT  the other device stopped answering."
}

# ---------------- dwell, before any correlation ------------------------------
# A band the phone is almost always on explains nothing about an event that
# happened while it was on it. The first version reported "band n41 crowds
# Bluetooth" against every drop on a phone that spent 99.7% of the window on
# n41, where chance alone put 98% of drops there. Measure the base rate first
# and say plainly when the comparison cannot carry weight.
$dwell = @{}
if ($sorted.Count -gt 1) {
  for ($i = 0; $i -lt $sorted.Count; $i++) {
    $stop = if ($i + 1 -lt $sorted.Count) { $sorted[$i+1].Time } else { $sorted[-1].Time }
    $sec = ($stop - $sorted[$i].Time).TotalSeconds
    if ($sec -gt 0) { $dwell[$sorted[$i].Band] = [double]$dwell[$sorted[$i].Band] + $sec }
  }
}
$totalDwell = 0.0; foreach ($v in $dwell.Values) { $totalDwell += $v }
$dominant = $null; $dominantShare = 0.0
W ""
W "===== how much of the window was spent on each band ====="
if ($totalDwell -le 0) { W "  (not enough state entries to measure)" }
else {
  foreach ($k in ($dwell.Keys | Sort-Object { -$dwell[$_] })) {
    $share = $dwell[$k] / $totalDwell
    if ($share -gt $dominantShare) { $dominantShare = $share; $dominant = $k }
    W ("  {0,-14} {1,7:N1} min   {2,6:P1} of the window" -f $k, ($dwell[$k]/60), $share)
  }
}

W ""
W "===== did a band change precede each Bluetooth drop? ====="
if ($dominantShare -gt 0.8) {
  W ("  READ THIS FIRST. The phone was on {0} for {1:P1} of this window, so almost" -f $dominant, $dominantShare)
  W ("  any event lands there by chance. 'The band crowds Bluetooth' below is then")
  W ("  a statement about where the phone lives, not about what caused anything.")
  W ("  Chance alone puts a single drop on {0} with probability {1:N3}." -f $dominant, $dominantShare)
  W ("  To separate the two you need the same route with that band excluded:")
  W ("    set the phone to LTE only, drive it again, and compare.")
  W ""
}
if ($sorted.Count -eq 0 -or $sessions.Count -eq 0) {
  W "  Need both a band history and Bluetooth sessions covering the same hours."
  W "  The ring buffer holds only the last few hours, so capture soon after driving."
} else {
  $window = 60   # seconds
  $hits = 0
  foreach ($s in ($sessions | Sort-Object End)) {
    $before = $sorted | Where-Object { $_.Time -le $s.End } | Select-Object -Last 1
    if (-not $before) { continue }
    $gap = [math]::Round(($s.End - $before.Time).TotalSeconds, 0)
    if ($gap -le $window) {
      $hits++
      $verdict = if (Crowds24 $before.Mhz) {
        "band {0} ({1:N0} MHz) crowds Bluetooth - {2}" -f $before.Band, $before.Mhz, `
          $(if ($s.Reason -eq 'CONNECTION_TIMEOUT') { "and the link timed out, which is what that looks like" } else { "but the link was closed deliberately ($($s.Reason)), not lost" })
      } else {
        "band {0} ({1:N0} MHz) does not crowd Bluetooth" -f $before.Band, $before.Mhz
      }
      W ("  {0}  {1}  dropped after {2}s  |  {3}s earlier: {4}" -f `
         $s.End.ToString('MM-dd HH:mm:ss'), $s.Peer, $s.Secs, $gap, $verdict)
    }
  }
  if ($hits -eq 0) { W "  No Bluetooth drop fell within $window s of a recorded radio state entry." }
}

# ---------------- per-band drop rate -----------------------------------------
W ""
W "===== drops per minute, by the band in use at the time ====="
if ($totalDwell -le 0 -or $sessions.Count -eq 0) { W "  (not enough data)" }
else {
  $byBand = @{}
  foreach ($s in $sessions) {
    $before = $sorted | Where-Object { $_.Time -le $s.End } | Select-Object -Last 1
    if (-not $before) { continue }
    if ($s.Reason -eq 'CONNECTION_TIMEOUT' -or $s.Reason -eq 'TRANSACTION_RESPONSE_TIMEOUT') {
      $byBand[$before.Band] = [int]$byBand[$before.Band] + 1
    }
  }
  foreach ($k in ($dwell.Keys | Sort-Object { -$dwell[$_] })) {
    $mins = $dwell[$k] / 60
    $n = [int]$byBand[$k]
    $rate = if ($mins -gt 0) { $n / $mins } else { 0 }
    $note = if ($mins -lt 5) { "   too little time on this band to compare" } else { "" }
    W ("  {0,-14} {1,3} timed-out link(s) in {2,7:N1} min = {3,6:N3}/min{4}" -f $k, $n, $mins, $rate, $note)
  }
  W ""
  W "  Only a band with real time on it can be compared against another. Where one"
  W "  band dominates, these rates are not evidence either way."
}

# ---------------- the link the audio actually uses ---------------------------
# Wireless Android Auto carries screen, touch and audio over Wi-Fi Direct; the
# Bluetooth link to the head unit is hands-free calling only. So the frequency
# of this link, not the Bluetooth band, is what matters.
#
# The structured parse is backed by printing the raw matching lines, because the
# exact shape of dumpsys output varies by build and a parse that silently finds
# nothing is the failure mode this project keeps running into.
W ""
W "===== the Wi-Fi Direct link Android Auto runs on ====="
$p2pSection = $false; $freqs = @(); $rawP2p = New-Object System.Collections.ArrayList
foreach ($line in $lines) {
  if ($line -match '^===== wifi direct state =====') { $p2pSection = $true; continue }
  if ($p2pSection -and $line -match '^===== ') { $p2pSection = $false; continue }
  if (-not $p2pSection) { continue }
  if ($line -match '(?i)(freq|frequency|channel|networkName|SSID|interface|isGO|owner|mGroup|P2P_|GroupStarted|GroupRemoved)') {
    [void]$rawP2p.Add($line.TrimEnd())
    foreach ($m in [regex]::Matches($line, '(?i)(?:freq(?:uency)?)\D{0,3}(\d{4})')) {
      $freqs += [int]$m.Groups[1].Value
    }
  }
}
if ($rawP2p.Count -eq 0) {
  W "  (nothing matched in the wifi direct section - the link may not have been up"
  W "   when this was captured. Capture while Android Auto is actually connected.)"
} else {
  if ($freqs.Count -gt 0) {
    foreach ($f in ($freqs | Sort-Object -Unique)) {
      $band = if ($f -ge 2400 -and $f -le 2500) { "2.4 GHz" }
              elseif ($f -ge 5100 -and $f -le 5900) { "5 GHz" }
              elseif ($f -ge 5925) { "6 GHz" } else { "unrecognised" }
      $note = if ($band -eq "2.4 GHz") {
                "   <- only ~$([int](2496 - $f)) MHz below n41. Coexistence is plausible here."
              } elseif ($band -eq "5 GHz") {
                "   <- far from n41. Cellular coexistence does not explain a drop on this link."
              } else { "" }
      W ("  link frequency {0} MHz  = {1}{2}" -f $f, $band, $note)
    }
  } else {
    W "  (no frequency found; raw lines below)"
  }
  W ""
  W "  raw lines from the Wi-Fi Direct dump:"
  foreach ($r in ($rawP2p | Select-Object -First 30)) { W ("    " + $r) }
}

# ---------------- what the watch itself recorded -----------------------------
# The app samples every six seconds and timestamps each Bluetooth audio route
# change the moment it happens. That is the only record of a brief dropout that
# recovers, since such a dropout never tears down the link. The capture has been
# collecting it; the timeline was not showing it, which made the single most
# direct piece of evidence invisible.
W ""
W "===== what Sentinel's watch recorded ====="
$inSentinel = $false; $sentinelLines = 0
foreach ($line in $lines) {
  if ($line -match '^===== Sentinel radio events =====') { $inSentinel = $true; continue }
  if ($inSentinel -and $line -match '^===== ') { $inSentinel = $false; continue }
  if (-not $inSentinel) { continue }
  if ($line -match '^#' -or -not $line.Trim()) { continue }
  if ($line -match '\(refused or empty') { W "  (the watch was not running, or wrote nothing)"; break }
  W ("  " + $line.TrimEnd())
  $sentinelLines++
}
if ($sentinelLines -eq 0 -and -not $inSentinel) { }
W ""
W "  [AUDIO] lines are Bluetooth audio appearing or disappearing, timestamped at"
W "  the instant it happened, with the band in use at that moment. Those are the"
W "  brief dropouts. Everything else there is the radio rules firing."

W ""
W "A drop that times out seconds after the radio moved into 2300-2700 MHz is the"
W "coexistence effect: the phone shares one front end between that band and the"
W "2.4 GHz Bluetooth uses, and quiets the short-range radio to protect the call."
W "A drop that was closed deliberately by either end is not that, whatever band"
W "was in use at the time."

Write-Host "Wrote $out" -ForegroundColor Green
Get-Content $out
