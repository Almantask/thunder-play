<#
.SYNOPSIS
    Mirrors the WAV music library into a mobile-sized AAC library for Thunder Play.

.DESCRIPTION
    Walks -Source for .wav files and writes matching .m4a files under -Dest, preserving the
    relative folder structure so each output path maps back to its source by simple substitution.
    That mapping is what lets the app move BOTH the .m4a and its source .wav to the trash folder;
    trashing only the derived file would let the next run resurrect the track.

    AAC-LC is used rather than Opus because Safari on iOS cannot play Opus, and the same files
    are streamed by the public playlist share player. The +faststart flag moves the moov atom to
    the front of the file, without which browsers cannot begin playback until the whole file has
    downloaded.

    A manifest records the size and modification time of each source, so re-runs only transcode
    what actually changed.

.EXAMPLE
    .\transcode.ps1
    Transcode everything new or changed.

.EXAMPLE
    .\transcode.ps1 -Watch
    Transcode, then keep running and convert new tracks as they land.

.EXAMPLE
    .\transcode.ps1 -WhatIf
    Show what would happen without touching anything.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string] $Source     = 'E:\Music-And-Fx-Generated-Library\music',
    [string] $Dest       = 'E:\Music-And-Fx-Generated-Library\music-mobile',
    [string] $Bitrate    = '128k',
    [int]    $SampleRate = 48000,
    [int]    $Jobs       = [Math]::Max(1, [Environment]::ProcessorCount - 1),
    [switch] $Watch,
    [switch] $Force,
    [switch] $NoPrune
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7+ is required (parallel transcoding). Current: $($PSVersionTable.PSVersion)"
}
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    throw "ffmpeg was not found on PATH. Install it, or add it to PATH, then re-run."
}
if (-not (Test-Path -LiteralPath $Source)) {
    throw "Source folder does not exist: $Source"
}

$ManifestPath = Join-Path $Dest '.manifest.json'
$ManifestVersion = 1
$SEP = [IO.Path]::DirectorySeparatorChar

function ConvertTo-RelKey {
    param([string] $Base, [string] $FullPath)
    # Manifest keys use forward slashes so they stay stable and readable across tools.
    ([IO.Path]::GetRelativePath($Base, $FullPath)).Replace($SEP, '/')
}

function ConvertFrom-RelKey {
    param([string] $Base, [string] $RelKey)
    Join-Path $Base ($RelKey.Replace('/', $SEP))
}

function Read-Manifest {
    # Timestamps are stored as UTC ticks, not ISO strings: ConvertFrom-Json silently
    # rehydrates ISO-8601 strings into DateTime, so a string round-trip never compares
    # equal and every run would re-transcode the whole library.
    if (-not (Test-Path -LiteralPath $ManifestPath)) { return @{} }
    try {
        $parsed = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($null -eq $parsed.entries) { return @{} }
        $map = @{}
        foreach ($p in $parsed.entries.PSObject.Properties) {
            $map[$p.Name] = @{ size = [int64] $p.Value.size; mtime = [int64] $p.Value.mtime }
        }
        return $map
    } catch {
        Write-Warning "Manifest unreadable ($($_.Exception.Message)); treating every track as new."
        return @{}
    }
}

function Write-Manifest {
    param([hashtable] $Map)
    $entries = [ordered] @{}
    foreach ($k in ($Map.Keys | Sort-Object)) {
        $entries[$k] = [ordered] @{ size = $Map[$k].size; mtime = $Map[$k].mtime }
    }
    $doc = [ordered] @{
        version    = $ManifestVersion
        bitrate    = $Bitrate
        sampleRate = $SampleRate
        updatedAt  = (Get-Date).ToUniversalTime().ToString('o')
        entries    = $entries
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ManifestPath) | Out-Null
    $doc | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ManifestPath -Encoding UTF8
}

function Get-Pending {
    param([hashtable] $Manifest)
    $pending = [System.Collections.Generic.List[object]]::new()
    foreach ($wav in (Get-ChildItem -LiteralPath $Source -Recurse -File -Filter '*.wav')) {
        $relWav  = ConvertTo-RelKey -Base $Source -FullPath $wav.FullName
        $relOut  = [IO.Path]::ChangeExtension($relWav, '.m4a')
        $outPath = ConvertFrom-RelKey -Base $Dest -RelKey $relOut
        $stamp   = @{ size = $wav.Length; mtime = $wav.LastWriteTimeUtc.Ticks }

        $known = $Manifest[$relWav]
        $upToDate = (-not $Force) -and
                    ($null -ne $known) -and
                    ($known.size -eq $stamp.size) -and
                    ($known.mtime -eq $stamp.mtime) -and
                    (Test-Path -LiteralPath $outPath)

        if (-not $upToDate) {
            $pending.Add([pscustomobject] @{
                Source = $wav.FullName
                Output = $outPath
                RelWav = $relWav
                Stamp  = $stamp
            })
        }
    }
    return $pending
}

function Invoke-Transcode {
    param([object[]] $Items)
    if ($null -eq $Items -or $Items.Count -eq 0) { return @() }

    $b = $Bitrate
    $sr = $SampleRate
    return $Items | ForEach-Object -ThrottleLimit $Jobs -Parallel {
        $item = $_
        $dir = Split-Path -Parent $item.Output
        if (-not (Test-Path -LiteralPath $dir)) {
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
        }
        # Write to a temp name and move on success, so an interrupted run never leaves a
        # truncated .m4a that a later run would mistake for complete output.
        $tmp = $item.Output + '.partial'
        $log = & ffmpeg -hide_banner -loglevel error -y -i $item.Source -vn -f ipod -c:a aac -b:a $using:b -ar $using:sr -movflags +faststart $tmp 2>&1
        if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $tmp)) {
            Move-Item -LiteralPath $tmp -Destination $item.Output -Force
            [pscustomobject] @{ Item = $item; Ok = $true; Error = $null }
        } else {
            if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
            [pscustomobject] @{ Item = $item; Ok = $false; Error = ($log -join '; ') }
        }
    }
}

function Remove-Orphans {
    param([hashtable] $Manifest)
    if ($NoPrune -or -not (Test-Path -LiteralPath $Dest)) { return 0 }

    $removed = 0
    foreach ($out in (Get-ChildItem -LiteralPath $Dest -Recurse -File -Filter '*.m4a')) {
        $relOut = ConvertTo-RelKey -Base $Dest -FullPath $out.FullName
        $relWav = [IO.Path]::ChangeExtension($relOut, '.wav')
        $srcPath = ConvertFrom-RelKey -Base $Source -RelKey $relWav
        if (-not (Test-Path -LiteralPath $srcPath)) {
            if ($PSCmdlet.ShouldProcess($out.FullName, 'Remove orphaned output')) {
                Remove-Item -LiteralPath $out.FullName -Force
                $Manifest.Remove($relWav) | Out-Null
                $removed++
            }
        }
    }

    # Clear out any directories the pruning left empty.
    Get-ChildItem -LiteralPath $Dest -Recurse -Directory |
        Sort-Object { $_.FullName.Length } -Descending |
        Where-Object { -not (Get-ChildItem -LiteralPath $_.FullName -Force | Select-Object -First 1) } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

    return $removed
}

function Invoke-Pass {
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    $manifest = Read-Manifest
    $pending  = @(Get-Pending -Manifest $manifest)
    $pruned   = Remove-Orphans -Manifest $manifest

    if ($pending.Count -eq 0) {
        if ($pruned -gt 0) { Write-Manifest -Map $manifest }
        Write-Host "Up to date. (pruned: $pruned)" -ForegroundColor Green
        return
    }

    if ($WhatIfPreference) {
        Write-Host "Would transcode $($pending.Count) file(s), prune $pruned" -ForegroundColor Yellow
        $pending | Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.RelWav)" }
        if ($pending.Count -gt 20) { Write-Host "  ... and $($pending.Count - 20) more" }
        return
    }

    Write-Host "Transcoding $($pending.Count) file(s) with $Jobs parallel job(s)..." -ForegroundColor Cyan
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $results = Invoke-Transcode -Items $pending

    $ok = 0
    $failed = 0
    foreach ($r in $results) {
        if ($r.Ok) {
            $manifest[$r.Item.RelWav] = $r.Item.Stamp
            $ok++
        } else {
            $failed++
            Write-Warning "FAILED $($r.Item.RelWav): $($r.Error)"
        }
    }
    Write-Manifest -Map $manifest
    $sw.Stop()

    $colour = if ($failed -gt 0) { 'Yellow' } else { 'Green' }
    Write-Host "Done in $([int]$sw.Elapsed.TotalSeconds)s - converted: $ok, failed: $failed, pruned: $pruned" -ForegroundColor $colour
    if ($failed -gt 0) { exit 1 }
}

Invoke-Pass

if ($Watch) {
    Write-Host "Watching $Source for changes. Press Ctrl+C to stop." -ForegroundColor Cyan
    $fsw = [IO.FileSystemWatcher]::new($Source, '*.wav')
    $fsw.IncludeSubdirectories = $true
    $fsw.EnableRaisingEvents = $true
    try {
        while ($true) {
            $change = $fsw.WaitForChanged('All', 1000)
            if ($change.TimedOut) { continue }
            # Coalesce bursts: Drive Desktop writes a file in several chunks, and a large WAV can
            # still be growing when the first event fires.
            Start-Sleep -Seconds 3
            while (-not ($fsw.WaitForChanged('All', 1500)).TimedOut) { }
            Write-Host "Change detected - running a pass..." -ForegroundColor DarkGray
            try { Invoke-Pass } catch { Write-Warning $_.Exception.Message }
        }
    } finally {
        $fsw.Dispose()
    }
}
