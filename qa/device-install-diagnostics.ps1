# Read-only by default. -Install requests one normal APK update attempt.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$AndroidSdk,
    [Parameter(Mandatory)][string]$Serial,
    [string]$OutputDirectory = './device-evidence',
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$tools = Join-Path $AndroidSdk 'build-tools/35.0.0'
$signer = Join-Path $tools 'apksigner.bat'
$aapt = Join-Path $tools 'aapt.exe'
foreach ($tool in @($adb, $signer, $aapt)) {
    if (!(Test-Path -LiteralPath $tool)) { throw "Required SDK tool missing: $tool" }
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $([IO.Path]::GetFileName($apkPath))" | Set-Content (Join-Path $OutputDirectory 'SHA256SUMS.txt')
$signature = & $signer verify --verbose --print-certs $apkPath 2>&1
$signature | Set-Content (Join-Path $OutputDirectory 'signature.txt')
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed; no installation attempted.' }
$badging = & $aapt dump badging $apkPath 2>&1
$badging | Set-Content (Join-Path $OutputDirectory 'apk-badging.txt')
if ($LASTEXITCODE -ne 0) { throw 'APK inspection failed; no installation attempted.' }
$packageLine = $badging | Where-Object { "$_" -match "^package: name='([^']+)'" } | Select-Object -First 1
if ("$packageLine" -notmatch "^package: name='(com\.aiorigin\.detector(?:\.debug)?)'") {
    throw 'Unexpected APK package; no installation attempted.'
}
$package = $Matches[1]
$state = & $adb -s $Serial get-state 2>&1
if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne 'device') {
    throw 'Device is unavailable or unauthorized. No security setting is changed by this script.'
}
$device = foreach ($property in @('ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.product.cpu.abilist')) {
    $value = & $adb -s $Serial shell getprop $property
    if ($LASTEXITCODE -ne 0) { throw "Cannot read $property" }
    "$property=$value"
}
$device | Set-Content (Join-Path $OutputDirectory 'device.txt')
$installed = & $adb -s $Serial shell dumpsys package $package 2>&1
$installed | Select-String 'versionCode=|versionName=|signatures=|Unable to find package' |
    ForEach-Object { $_.Line } | Set-Content (Join-Path $OutputDirectory 'installed-package.txt')
# Inspect the existing base APK's real signing certificate instead of treating
# dumpsys' internal signature hash as a certificate SHA-256 fingerprint.
$packagePaths = & $adb -s $Serial shell pm path $package 2>&1
$basePath = $packagePaths | Where-Object { "$_" -match '^package:/.*/base\.apk\s*$' } | Select-Object -First 1
if ($basePath) {
    $remoteApk = "$basePath".Trim().Substring('package:'.Length)
    $localInstalled = Join-Path $OutputDirectory 'installed-base.apk'
    & $adb -s $Serial pull $remoteApk $localInstalled | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect installed APK certificate; no installation attempted.' }
    $oldSignature = & $signer verify --verbose --print-certs $localInstalled 2>&1
    $oldSignature | Set-Content (Join-Path $OutputDirectory 'installed-signature.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Installed APK signature inspection failed; no installation attempted.' }
}
if ($Install) {
    # No uninstall, downgrade, signature bypass, permission grant or policy change.
    $result = & $adb -s $Serial install -r $apkPath 2>&1
    $installCode = $LASTEXITCODE
    $result | Set-Content (Join-Path $OutputDirectory 'install-result.txt')
    if ($installCode -ne 0 -or !($result | Where-Object { "$_".Trim() -eq 'Success' })) {
        throw 'Normal installation failed. Read install-result.txt; do not disable device protections.'
    }
}
Write-Output "Evidence saved to $OutputDirectory. Physical UI behavior still requires device testing."
