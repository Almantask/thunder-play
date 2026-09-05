<#
.SYNOPSIS
    Verifies the Drive service account can see and read the music library.

.DESCRIPTION
    Performs the same three steps the app does - mint a JWT, exchange it for an access token, list
    the library - so a setup problem can be diagnosed on the PC instead of on a phone.

    Checks, in order:
      1. the key file parses
      2. Google accepts the signed assertion
      3. the library folder has actually been shared with the service account
      4. music-mobile/ and music/ are present and populated
      5. the app's own folder query is well formed

.EXAMPLE
    pwsh -File tools\check-drive.ps1
#>
[CmdletBinding()]
param(
    [string] $KeyPath = (Join-Path $PSScriptRoot '..\app\src\main\assets\drive-service-account.json'),
    [string] $LibraryName = 'Music-And-Fx-Generated-Library'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step { param([string] $Text) Write-Host "`n== $Text" -ForegroundColor Cyan }
function Write-Ok   { param([string] $Text) Write-Host "   OK   $Text" -ForegroundColor Green }
function Write-Bad  { param([string] $Text) Write-Host "   FAIL $Text" -ForegroundColor Red }

function ConvertTo-Base64Url {
    param([byte[]] $Bytes)
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# ---------------------------------------------------------------- 1. key

Write-Step "Service account key"
if (-not (Test-Path -LiteralPath $KeyPath)) {
    Write-Bad "No key at $KeyPath - see docs/SETUP.md step 2."
    exit 1
}
$key = Get-Content -LiteralPath $KeyPath -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Ok "client_email : $($key.client_email)"
Write-Ok "project_id   : $($key.project_id)"

# ---------------------------------------------------------------- 2. token

Write-Step "Access token"
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = @{ alg = 'RS256'; typ = 'JWT'; kid = $key.private_key_id } | ConvertTo-Json -Compress
$claims = [ordered] @{
    iss   = $key.client_email
    scope = 'https://www.googleapis.com/auth/drive'
    aud   = $key.token_uri
    exp   = $now + 3600
    iat   = $now
} | ConvertTo-Json -Compress

$signingInput = (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))) + '.' +
                (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($claims)))

$pem = $key.private_key -replace '-----(BEGIN|END) PRIVATE KEY-----', ''
$der = [Convert]::FromBase64String(($pem -replace '\s', ''))
$rsa = [System.Security.Cryptography.RSA]::Create()
$rsa.ImportPkcs8PrivateKey($der, [ref] 0)
$signature = $rsa.SignData(
    [Text.Encoding]::UTF8.GetBytes($signingInput),
    [System.Security.Cryptography.HashAlgorithmName]::SHA256,
    [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
$jwt = $signingInput + '.' + (ConvertTo-Base64Url $signature)

try {
    $token = Invoke-RestMethod -Method Post -Uri $key.token_uri -Body @{
        grant_type = 'urn:ietf:params:oauth:grant-type:jwt-bearer'
        assertion  = $jwt
    }
    Write-Ok "token acquired, expires in $($token.expires_in)s"
} catch {
    Write-Bad "Token exchange rejected: $($_.Exception.Message)"
    Write-Host "        Usually a clock skew of more than a few minutes, or a revoked key." -ForegroundColor DarkGray
    exit 1
}
$auth = @{ Authorization = "Bearer $($token.access_token)" }

function Invoke-Drive {
    param([string] $Query, [string] $Fields = 'files(id,name,mimeType)')
    $uri = 'https://www.googleapis.com/drive/v3/files' +
           '?q=' + [Uri]::EscapeDataString($Query) +
           '&fields=' + [Uri]::EscapeDataString("nextPageToken,$Fields") +
           '&pageSize=1000'
    Invoke-RestMethod -Method Get -Uri $uri -Headers $auth
}

# ---------------------------------------------------------------- 3. sharing

Write-Step "Library folder shared with the service account"
$folderMime = 'application/vnd.google-apps.folder'
$query = "name='$LibraryName' and mimeType='$folderMime' and trashed=false"
try {
    $found = Invoke-Drive -Query $query
} catch {
    Write-Bad "files.list rejected: $($_.Exception.Message)"
    Write-Host "        Query was: $query" -ForegroundColor DarkGray
    exit 1
}
if (-not $found.files -or $found.files.Count -eq 0) {
    Write-Bad "'$LibraryName' is not visible to $($key.client_email)."
    Write-Host "        Share the folder with that address as Editor - docs/SETUP.md step 3." -ForegroundColor DarkGray
    exit 1
}
$root = $found.files[0]
Write-Ok "found, id $($root.id)"

# ---------------------------------------------------------------- 4. contents

$exit = 0
foreach ($sub in @('music-mobile', 'music')) {
    Write-Step "$sub/"
    $child = Invoke-Drive -Query "'$($root.id)' in parents and name='$sub' and trashed=false"
    if (-not $child.files -or $child.files.Count -eq 0) {
        if ($sub -eq 'music-mobile') {
            Write-Bad "missing - run tools\transcode\transcode.ps1 and let Drive upload."
            $exit = 1
        } else {
            Write-Bad "missing - the app cannot trash source WAVs without it."
        }
        continue
    }

    # Count leaves the way the app does: walk the folder tree breadth first.
    $frontier = @($child.files[0].id)
    $files = 0
    $folders = 0
    while ($frontier.Count -gt 0) {
        $next = @()
        foreach ($id in $frontier) {
            $page = Invoke-Drive -Query "'$id' in parents and trashed=false"
            foreach ($f in $page.files) {
                if ($f.mimeType -eq $folderMime) { $next += $f.id; $folders++ } else { $files++ }
            }
        }
        $frontier = $next
    }
    Write-Ok "$files file(s) across $folders subfolder(s)"
    if ($sub -eq 'music-mobile' -and $files -eq 0) {
        Write-Bad "no audio yet - Drive Desktop may still be uploading."
        $exit = 1
    }
}

Write-Step "Result"
if ($exit -eq 0) {
    Write-Host "   Drive is wired up correctly. Refresh in the app should work." -ForegroundColor Green
} else {
    Write-Host "   Something above needs attention before the app can list tracks." -ForegroundColor Yellow
}
exit $exit
