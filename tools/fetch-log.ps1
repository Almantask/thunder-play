<#
.SYNOPSIS
    Downloads the app's diagnostics log straight from Drive.

.DESCRIPTION
    Drive for Desktop can leave a local placeholder that looks empty while the real content sits in
    the cloud, so reading the mirrored file is not reliable. This fetches the authoritative copy
    through the API.
#>
[CmdletBinding()]
param(
    [string] $KeyPath = (Join-Path $PSScriptRoot '..\app\src\main\assets\drive-service-account.json'),
    [string] $LogFolder = '_ThunderPlayLogs',
    [int] $Tail = 0,
    [switch] $List
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function ConvertTo-Base64Url { param([byte[]] $Bytes)
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

$key = Get-Content -LiteralPath $KeyPath -Raw -Encoding UTF8 | ConvertFrom-Json
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = @{ alg='RS256'; typ='JWT'; kid=$key.private_key_id } | ConvertTo-Json -Compress
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

function Find-One {
    param([string] $Query)
    $uri = 'https://www.googleapis.com/drive/v3/files?q=' + [Uri]::EscapeDataString($Query) +
           '&fields=' + [Uri]::EscapeDataString('files(id,name,size,modifiedTime)')
    (Invoke-RestMethod -Method Get -Uri $uri -Headers $auth).files | Select-Object -First 1
}

function Find-All {
    param([string] $Query)
    $uri = 'https://www.googleapis.com/drive/v3/files?q=' + [Uri]::EscapeDataString($Query) +
           '&fields=' + [Uri]::EscapeDataString('files(id,name,size,modifiedTime,mimeType)') +
           '&pageSize=100'
    @((Invoke-RestMethod -Method Get -Uri $uri -Headers $auth).files)
}

$folderMime = 'application/vnd.google-apps.folder'
$folder = Find-One "name='$LogFolder' and mimeType='$folderMime' and trashed=false"
if (-not $folder) {
    throw ("No $LogFolder folder in Drive yet. On the phone: Settings -> Diagnostics -> Send, " +
           "then save it into $LogFolder from the share sheet.")
}

# The filename varies - the old in-app uploader wrote diagnostics.log, the share sheet produces
# thunder-play-diagnostics.txt - so take whatever is newest instead of guessing.
$candidates = @(Find-All "'$($folder.id)' in parents and trashed=false" |
    Where-Object { $_.mimeType -ne $folderMime } |
    Sort-Object -Property modifiedTime -Descending)

if ($candidates.Count -eq 0) { throw "$LogFolder is empty. Send a log from the phone first." }

if ($List) {
    Write-Host "Logs in ${LogFolder}:" -ForegroundColor Cyan
    $candidates | ForEach-Object {
        "  {0,-42} {1,9} bytes  {2}" -f $_.name, $_.size, ([DateTime]$_.modifiedTime).ToLocalTime()
    }
    exit 0
}

$file = $candidates[0]
$bytes = if ($file.size) { [int64] $file.size } else { 0 }
if ($bytes -eq 0) {
    Write-Warning ("$($file.name) is 0 bytes. If the app uploaded it that is expected - a " +
        "service account cannot write file content to Drive. Use the share sheet instead.")
}

Write-Host "$($file.name)  $($file.size) bytes  modified $(([DateTime]$file.modifiedTime).ToLocalTime())" -ForegroundColor Cyan
Write-Host ("-" * 72)

$content = Invoke-RestMethod -Method Get -Headers $auth `
    -Uri "https://www.googleapis.com/drive/v3/files/$($file.id)?alt=media"

if ($Tail -gt 0) {
    ($content -split "`n" | Select-Object -Last $Tail) -join "`n"
} else {
    $content
}
