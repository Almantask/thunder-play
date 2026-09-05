<#
.SYNOPSIS
    Lists what the service account can see in Google Drive's trash.

.DESCRIPTION
    Drive keeps deleted files for 30 days, so a folder that has vanished is usually recoverable.
    This reports what is there and when it was removed, without changing anything.

    Read-only: it never restores or deletes. Restoring is done from the Drive web UI, deliberately,
    so nothing here can make a bad situation worse.
#>
[CmdletBinding()]
param(
    [string] $KeyPath = (Join-Path $PSScriptRoot '..\app\src\main\assets\drive-service-account.json'),
    [int] $Limit = 40
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function ConvertTo-Base64Url {
    param([byte[]] $Bytes)
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$key = Get-Content -LiteralPath $KeyPath -Raw -Encoding UTF8 | ConvertFrom-Json
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = @{ alg = 'RS256'; typ = 'JWT'; kid = $key.private_key_id } | ConvertTo-Json -Compress
$claims = [ordered] @{
    iss = $key.client_email
    scope = 'https://www.googleapis.com/auth/drive'
    aud = $key.token_uri
    exp = $now + 3600
    iat = $now
} | ConvertTo-Json -Compress

$signingInput = (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))) + '.' +
                (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($claims)))
$der = [Convert]::FromBase64String((($key.private_key -replace '-----(BEGIN|END) PRIVATE KEY-----','') -replace '\s',''))
$rsa = [System.Security.Cryptography.RSA]::Create()
$rsa.ImportPkcs8PrivateKey($der, [ref] 0)
$jwt = $signingInput + '.' + (ConvertTo-Base64Url $rsa.SignData(
    [Text.Encoding]::UTF8.GetBytes($signingInput),
    [System.Security.Cryptography.HashAlgorithmName]::SHA256,
    [System.Security.Cryptography.RSASignaturePadding]::Pkcs1))

$token = Invoke-RestMethod -Method Post -Uri $key.token_uri -Body @{
    grant_type = 'urn:ietf:params:oauth:grant-type:jwt-bearer'
    assertion  = $jwt
}
$auth = @{ Authorization = "Bearer $($token.access_token)" }

$fields = 'files(id,name,mimeType,size,trashedTime,explicitlyTrashed,parents,owners(emailAddress))'
$uri = 'https://www.googleapis.com/drive/v3/files' +
       '?q=' + [Uri]::EscapeDataString('trashed=true') +
       '&fields=' + [Uri]::EscapeDataString("nextPageToken,$fields") +
       '&pageSize=1000'   # trashedTime is not a valid orderBy field; sorted below instead

$result = Invoke-RestMethod -Method Get -Uri $uri -Headers $auth
# Re-wrap: Sort-Object unwraps a zero- or one-element result back to a scalar.
$files = @(@($result.files) | Sort-Object -Property trashedTime -Descending)

Write-Host "`n== Trash visible to $($key.client_email)" -ForegroundColor Cyan
if ($files.Count -eq 0) {
    Write-Host "   Nothing in the trash." -ForegroundColor Yellow
    Write-Host "   Note a service account only sees what was shared with it - check the Drive" -ForegroundColor DarkGray
    Write-Host "   web UI's own Trash as well, which shows everything you own." -ForegroundColor DarkGray
    exit 0
}

$folderMime = 'application/vnd.google-apps.folder'
$folders = @($files | Where-Object { $_.mimeType -eq $folderMime })
$audio = @($files | Where-Object { $_.name -match '\.(wav|m4a)$' })

Write-Host ("   {0} item(s): {1} folder(s), {2} audio file(s)" -f $files.Count, $folders.Count, $audio.Count) -ForegroundColor Green

$bytes = ($files | Where-Object { $_.size } | Measure-Object -Property size -Sum).Sum
if ($bytes) { Write-Host ("   {0:N1} MB recoverable" -f ($bytes / 1MB)) -ForegroundColor Green }

Write-Host "`n== Most recently trashed" -ForegroundColor Cyan
$files | Select-Object -First $Limit | ForEach-Object {
    $kind = if ($_.mimeType -eq $folderMime) { '[dir] ' } else { '      ' }
    $when = if ($_.trashedTime) { ([DateTime]$_.trashedTime).ToLocalTime().ToString('HH:mm:ss') } else { '--:--:--' }
    "  $when  $kind$($_.name)"
}
if ($files.Count -gt $Limit) { "  ... and $($files.Count - $Limit) more" }

Write-Host "`n== To restore" -ForegroundColor Cyan
Write-Host "   drive.google.com -> Trash -> select -> Restore. Files stay for 30 days." -ForegroundColor DarkGray
Write-Host "   Pause Drive for Desktop first, so a resync cannot re-delete them." -ForegroundColor DarkGray
