# Windows port of the parts of harness/run.sh a bench run needs (no MangoHud, no JFR, no recording).
#
#   harness/run-win.ps1 -Label <name> [-Mode bench|drive|verify] [-Flag k=v ...] [-Prop k=v ...]
#                       [-Option k=v ...] [-RouteSeconds N] [-QuitAfter secs] [-Dashboard]
#
# What it does (same order as run.sh):
#   1. installs the pzopt-harness Lua mod into %USERPROFILE%\Zomboid\mods and enables it in mods\default.txt
#   2. rebuilds Saves\Sandbox\pzopt-bench from the template unpacked out of harness/bench-save (byte-identical
#      chunk data every run); PZDashboard is dropped from the save's mods.txt unless -Dashboard
#   3. points latestSave.ini at the bench save, writes Zomboid\Lua\pzopt-harness.txt and <game>\pzopt.properties
#   4. launches the game through Steam (steam://rungameid/108600), samples CPU/GPU into sysmon.csv while it runs
#      (same columns as harness/sysmon.sh), waits for the process to exit
#   5. collects console.txt, pzopt-*.out, sysmon.csv, crash dumps into harness\runs\<label>-<timestamp>\ and
#      restores every file it touched.
# The Java harness ends the run itself: no MangoHud in the process means no linger, quit at route end.
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [string]$Mode = "bench",
  [string[]]$Flag = @(),
  [string[]]$Prop = @("instrument=true"),
  [string[]]$Option = @(),   # game options.ini key=value for the run (run.sh --option), restored after
  [int]$RouteSeconds = 100,
  [string]$QuitAfter = "",
  [switch]$Dashboard,
  [string]$PZ = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
)
$ErrorActionPreference = "Stop"
# `powershell -File run-win.ps1 -Prop a=1,b=2` hands the script ONE string "a=1,b=2" (no array splitting on the
# -File command line); split every element on commas so both call styles give one key per line; only a comma before
# the next key= splits, so values with commas stay whole (start=12450,1280, path=8010,11204.5/9210,11204.5)
$Prop = @($Prop | ForEach-Object { $_ -split ',(?=[A-Za-z_][A-Za-z0-9_.]*=)' } | Where-Object { $_ })
$Flag = @($Flag | ForEach-Object { $_ -split ',(?=[A-Za-z_][A-Za-z0-9_.]*=)' } | Where-Object { $_ })
$Option = @($Option | ForEach-Object { $_ -split ',(?=[A-Za-z_][A-Za-z0-9_.]*=)' } | Where-Object { $_ })
$Repo = Split-Path -Parent $PSScriptRoot
$Z = "$env:USERPROFILE\Zomboid"
$ModSrc = "$Repo\harness\mod\pzopt-harness"
$Template = "$Z\Saves\Sandbox\pzopt-bench-template"
$Bench = "$Z\Saves\Sandbox\pzopt-bench"
$FlagFile = "$Z\Lua\pzopt-harness.txt"
$Runs = "$Repo\harness\runs"

if (Get-Process -Name ProjectZomboid64 -ErrorAction SilentlyContinue) { throw "the game is already running" }
if (-not (Test-Path "$PZ\pzopt\Overrides.class")) { throw "overrides not installed in $PZ (docs/windows-test.md step 2)" }
if (-not (Test-Path $Template)) {
  Write-Host "unpacking bench save template"
  New-Item -ItemType Directory -Force "$Z\Saves\Sandbox" | Out-Null
  tar -xf "$Repo\harness\bench-save\pzopt-bench-template.tar.zst" -C "$Z\Saves\Sandbox"
  if ($LASTEXITCODE -ne 0) { throw "tar failed" }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = "$Runs\$Label-$stamp"
New-Item -ItemType Directory -Force $out | Out-Null

# --- files we touch, restored in finally ---
$backups = @{}
function Backup([string]$path) {
  if (Test-Path $path) { $backups[$path] = [System.IO.File]::ReadAllBytes($path) } else { $backups[$path] = $null }
}
function Restore() {
  foreach ($k in $backups.Keys) {
    if ($null -eq $backups[$k]) { Remove-Item -LiteralPath $k -Force -ErrorAction SilentlyContinue }
    else { [System.IO.File]::WriteAllBytes($k, $backups[$k]) }
  }
  Remove-Item -LiteralPath $FlagFile -Force -ErrorAction SilentlyContinue
}
function EnableMod([string]$modsTxt, [string]$id) {
  $txt = [System.IO.File]::ReadAllText($modsTxt)
  if ($txt -match "mod = $id,") { return }
  $nl = if ($txt -match "`r`n") { "`r`n" } else { "`n" }
  $new = [regex]::Replace($txt, "(?m)^mods\r?\n\{\r?\n", { param($m) $m.Value + "    mod = $id,$nl" }, 1)
  if ($new -eq $txt) { throw "could not enable $id in $modsTxt" }
  [System.IO.File]::WriteAllText($modsTxt, $new)
}
function DisableMod([string]$modsTxt, [string]$id) {
  $txt = [System.IO.File]::ReadAllText($modsTxt)
  $new = [regex]::Replace($txt, "(?m)^    mod = $id,\r?\n", "")
  [System.IO.File]::WriteAllText($modsTxt, $new)
}

$sysmon = $null
try {
  # 1. harness mod
  if (Test-Path "$Z\mods\pzopt-harness") { Remove-Item -Recurse -Force "$Z\mods\pzopt-harness" }
  Copy-Item -Recurse $ModSrc "$Z\mods\pzopt-harness"
  Backup "$Z\mods\default.txt"
  EnableMod "$Z\mods\default.txt" "pzopt-harness"

  # 2. bench save from the template
  if (Test-Path $Bench) { Remove-Item -Recurse -Force $Bench }
  Copy-Item -Recurse $Template $Bench
  EnableMod "$Bench\mods.txt" "pzopt-harness"
  if (-not $Dashboard) { DisableMod "$Bench\mods.txt" "PZDashboard" }

  # 3. latestSave.ini, flag file, pzopt.properties
  Backup "$Z\latestSave.ini"
  [System.IO.File]::WriteAllText("$Z\latestSave.ini", "pzopt-bench`r`nSandbox`r`n")
  $flags = @("mode=$Mode", "dashboard=$(if ($Dashboard) { 'enabled' } else { 'disabled' })")
  if ($QuitAfter) { $flags += "quit_after=$QuitAfter" }
  if (-not ($Flag | Where-Object { $_ -like 'settle=*' })) { $flags += "settle=5" }
  $flags += $Flag
  Remove-Item -LiteralPath $FlagFile -Force -ErrorAction SilentlyContinue
  [System.IO.File]::WriteAllText($FlagFile, (($flags -join "`n") + "`n"))
  if ($Option.Count -gt 0) {
    Backup "$Z\options.ini"
    $ini = [System.IO.File]::ReadAllText("$Z\options.ini")
    foreach ($o in $Option) {
      $k = $o.Split('=')[0]
      if ($ini -match "(?m)^$([regex]::Escape($k))=") { $ini = [regex]::Replace($ini, "(?m)^$([regex]::Escape($k))=[^`r`n]*", { param($m) $o }) }
      else { $ini = $ini.TrimEnd("`r", "`n") + "`r`n$o`r`n" }
    }
    [System.IO.File]::WriteAllText("$Z\options.ini", $ini)
  }
  Backup "$PZ\pzopt.properties"
  [System.IO.File]::WriteAllText("$PZ\pzopt.properties", (($Prop -join "`n") + "`n"))
  Remove-Item "$Z\pzopt-*.out", "$Z\pzopt-schedule.out", "$Z\pzopt-logdone", "$Z\console.txt" -Force -ErrorAction SilentlyContinue
  $preCrash = @(Get-ChildItem $PZ, $Z -Filter "hs_err_pid*.log" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })

  # 4. sampler + launch
  $launchEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $sysmon = Start-Job -ArgumentList "$out\sysmon.csv" -ScriptBlock {
    param($csv)
    "epoch_ms,cpu_pct,cpu_busiest_core_pct,gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib,game_cpu_pct,bat_w" | Out-File $csv -Encoding ascii
    while ($true) {
      try {
        $s = Get-Counter -Counter '\Processor(_Total)\% Processor Time', '\Processor(*)\% Processor Time', '\Process(ProjectZomboid64)\% Processor Time' -SampleInterval 1 -MaxSamples 1 -ErrorAction SilentlyContinue
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $tot = ($s.CounterSamples | Where-Object { $_.Path -like '*\processor(_total)\*' }).CookedValue
        $max = ($s.CounterSamples | Where-Object { $_.Path -like '*\processor(*' -and $_.Path -notlike '*(_total)*' } | Measure-Object CookedValue -Maximum).Maximum
        $game = ($s.CounterSamples | Where-Object { $_.Path -like '*\process(projectzomboid64)\*' }).CookedValue
        $gpu = (& nvidia-smi --query-gpu=utilization.gpu,clocks.sm,clocks.mem,power.draw,temperature.gpu,memory.used --format=csv,noheader,nounits 2>$null | Select-Object -First 1) -replace ' ', ''
        if (-not $gpu) { $gpu = ",,,,," }
        $gameStr = if ($null -ne $game) { [math]::Round($game, 1) } else { "" }
        "$now,$([math]::Round($tot,1)),$([math]::Round($max,1)),$gpu,$gameStr," | Out-File $csv -Append -Encoding ascii
      } catch { Start-Sleep -Seconds 1 }
    }
  }
  Write-Host "launching app 108600 (mode=$Mode); output -> $out"
  $start = Get-Date
  Start-Process "steam://rungameid/108600"
  $proc = $null
  for ($i = 0; $i -lt 120; $i++) {
    $proc = Get-Process -Name ProjectZomboid64 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($proc) { break }
    Start-Sleep -Seconds 1
  }
  if (-not $proc) { throw "game process did not appear within 120 s" }
  Write-Host "game running (pid $($proc.Id)); waiting for exit"
  $gone = 0
  while ($gone -lt 3) {
    if (Get-Process -Name ProjectZomboid64 -ErrorAction SilentlyContinue) { $gone = 0 } else { $gone++ }
    Start-Sleep -Seconds 2
  }
  $end = Get-Date
  Stop-Job $sysmon -ErrorAction SilentlyContinue; Remove-Job $sysmon -Force -ErrorAction SilentlyContinue; $sysmon = $null
  Start-Sleep -Seconds 2

  # 5. collect
  $crashed = 0
  foreach ($h in (Get-ChildItem $PZ, $Z -Filter "hs_err_pid*.log" -ErrorAction SilentlyContinue)) {
    if ($preCrash -contains $h.FullName) { continue }
    $crashed = 1
    Move-Item $h.FullName "$out\$($h.Name)"
    $frame = Select-String -Path "$out\$($h.Name)" -Pattern '^# C  \[' | Select-Object -First 1
    Write-Host "CRASH: $($frame.Line)"
  }
  if (Test-Path "$Z\console.txt") { Copy-Item "$Z\console.txt" "$out\console.txt" } else { Write-Host "no console.txt written" }
  Get-ChildItem "$Z" -Filter "pzopt-*.out" | Copy-Item -Destination $out
  Copy-Item "$PZ\pzopt.properties" "$out\pzopt.properties"
  Copy-Item "$PZ\ProjectZomboid64.json" "$out\ProjectZomboid64.json"
  $secs = [int]($end - $start).TotalSeconds
  @("layout=windows", "mode=$Mode", "crashed=$crashed", "attempts=1", "jfr=0", "game_profiler=0", "gc=default",
    "no_dashboard=$(if ($Dashboard) { 0 } else { 1 })", "mangohud_secs=", "lead=dynamic", "route_seconds=$RouteSeconds",
    "renderer=nvidia", "record=0", "launcher=steam", "launch_epoch=$launchEpoch", "run_seconds=$secs", "flags=$($Flag -join ' ')") |
    Out-File "$out\run.opts" -Encoding ascii
  Write-Host "run took ${secs}s$(if ($crashed) { ' (CRASHED)' }); log at $out\console.txt"
  Write-Host "--- [pzopt] lines:"
  Select-String -Path "$out\console.txt" -Pattern "\[pzopt" | Select-Object -First 40 | ForEach-Object { $_.Line -replace '^LOG  : General      f:0> ', '' }
  Write-Host "--- harness lines:"
  Select-String -Path "$out\console.txt" -Pattern "harness" | ForEach-Object { $_.Line -replace '^LOG  : General      f:0> ', '' } | Select-Object -First 60
  Write-Host "--- errors:"
  Select-String -Path "$out\console.txt" -Pattern "exception|error" -CaseSensitive:$false | Where-Object { $_.Line -notmatch 'ERROR: 0:0|GL_' } | Select-Object -First 10 | ForEach-Object { $_.Line }
} finally {
  if ($sysmon) { Stop-Job $sysmon -ErrorAction SilentlyContinue; Remove-Job $sysmon -Force -ErrorAction SilentlyContinue }
  Restore
}
