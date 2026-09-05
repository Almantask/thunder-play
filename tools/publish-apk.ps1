<#
.SYNOPSIS
    Builds the debug APK and drops it into the Google Drive folder so a phone can download it.

.DESCRIPTION
    Avoids needing USB, a LAN server, or a firewall exception: Drive for Desktop uploads the file
    and the Drive app on the phone downloads it. Works over mobile data too, not just the same
    network.

    The APK lands in a folder that is deliberately NOT music/ or music-mobile/, so the app's
    catalog scan never sees it.

    Note the built APK embeds the Drive service-account key once you have completed setup, so
    treat it as a secret: keep it in a Drive folder you have not shared with anyone.

.EXAMPLE
    pwsh -File tools\publish-apk.ps1
    Build and publish to the default drop folder.

.EXAMPLE
    pwsh -File tools\publish-apk.ps1 -SkipBuild
    Publish whatever was built last.
#>
[CmdletBinding()]
param(
    [string] $Destination = 'E:\Music-And-Fx-Generated-Library\_apk',
    [switch] $SkipBuild,
    [switch] $Release,
    [switch] $WaitForSync,
    [int]    $SyncTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$variant = if ($Release) { 'release' } else { 'debug' }
$apk = Join-Path $repoRoot "app\build\outputs\apk\$variant\app-$variant.apk"

if (-not $SkipBuild) {
    $task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
    Write-Host "Building $task..." -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'gradlew.bat') $task
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $apk)) {
    throw "No APK at $apk. Run without -SkipBuild first."
}

New-Item -ItemType Directory -Force -Path $Destination | Out-Null

# A stable name means the phone always overwrites the same download instead of collecting
# app-debug(1).apk, app-debug(2).apk and so on.
$target = Join-Path $Destination 'thunder-play.apk'
Copy-Item -LiteralPath $apk -Destination $target -Force

$item  = Get-Item -LiteralPath $target
$size  = [math]::Round($item.Length / 1MB, 1)
$built = (Get-Item -LiteralPath $apk).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
$hash  = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.Substring(0, 12).ToLower()

Write-Host ""
Write-Host "Published $size MB" -ForegroundColor Green
Write-Host "  path  : $target"
Write-Host "  built : $built"
Write-Host "  sha256: $hash"

if ($WaitForSync) {
    # The phone downloads whatever Drive currently holds. Reporting "done" before the upload has
    # flushed is how someone ends up installing the previous build and concluding the fix failed.
    $pendingDir = Join-Path (Split-Path -Parent $Destination) '.tmp.driveupload'
    $deadline = (Get-Date).AddSeconds($SyncTimeoutSeconds)
    $sawPending = $false

    Write-Host ""
    Write-Host "Waiting for Drive to finish uploading..." -ForegroundColor Cyan
    while ((Get-Date) -lt $deadline) {
        $pending = @(Get-ChildItem -LiteralPath $pendingDir -Force -ErrorAction SilentlyContinue)
        if ($pending.Count -gt 0) { $sawPending = $true; Start-Sleep -Seconds 2; continue }
        # An empty queue immediately after a copy usually means Drive has not noticed yet, so
        # require it to stay empty briefly before believing it.
        Start-Sleep -Seconds 3
        $pending = @(Get-ChildItem -LiteralPath $pendingDir -Force -ErrorAction SilentlyContinue)
        if ($pending.Count -eq 0) {
            if ($sawPending) { Write-Host "Upload complete." -ForegroundColor Green }
            else { Write-Host "Upload queue is empty (Drive may have finished already)." -ForegroundColor Green }
            break
        }
    }
    if ((Get-Date) -ge $deadline) {
        Write-Warning "Still uploading after $SyncTimeoutSeconds s. Check the Drive tray icon before installing."
    }
}

Write-Host ""
Write-Host "On the phone: Google Drive -> _apk/thunder-play.apk -> download -> tap to install." -ForegroundColor DarkGray
Write-Host "Check the file's date matches the build time above, or you may install a cached copy." -ForegroundColor DarkGray
