# sentinel-pull.ps1
#
# Safely pull every Sentinel log off the phone into a fresh, timestamped folder.
# It never overwrites a previous pull, so nothing you capture can be lost by
# pulling again. Run it before you ever uninstall, reinstall, or clear anything.
#
#   powershell -ExecutionPolicy Bypass -File sentinel-pull.ps1
#
# (or just paste the lines below into a PowerShell window)

$pkg  = "com.bluetier.sentinel"
$dev  = "/sdcard/Android/data/$pkg/files"
$stamp = Get-Date -Format "yyyy-MM-dd_HHmm"
$dest = Join-Path $HOME "sentinel-pulls\$stamp"

New-Item -ItemType Directory -Force $dest | Out-Null

# Drop any stray wireless adb target so "more than one device" can't block us.
adb disconnect 2>$null | Out-Null

Write-Host "Pulling $dev  ->  $dest" -ForegroundColor Cyan
adb pull "$dev/." "$dest" 2>&1 | Select-String "pulled|error|skipped"

Write-Host "`nFiles pulled:" -ForegroundColor Cyan
Get-ChildItem $dest -ErrorAction SilentlyContinue | Select-Object Name, Length, LastWriteTime

# Is the watch actually recording right now? watch-status.tsv answers it.
$status = Join-Path $dest "watch-status.tsv"
if (Test-Path $status) {
    Write-Host "`nWatch status (radio=live means it's recording properly):" -ForegroundColor Cyan
    Get-Content $status
} else {
    Write-Host "`nNo watch-status.tsv - the watch has not been started since install." -ForegroundColor Yellow
}

# How much radio data came down, and over what span.
$radio = Join-Path $dest "radio.log"
if (Test-Path $radio) {
    $l = Get-Content $radio
    if ($l.Count -gt 0) {
        Write-Host "`nradio.log: $($l.Count) samples, $($l[0].Substring(0,19)) -> $($l[-1].Substring(0,19))" -ForegroundColor Green
    }
}
