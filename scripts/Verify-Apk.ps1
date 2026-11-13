param(
    [string]$Apk = "$PSScriptRoot/../app/build/outputs/apk/debug/app-debug.apk",
    [string]$SdkRoot,
    [string]$JdkRoot = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path "$PSScriptRoot/..").Path
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
if (-not $SdkRoot) {
    $localProperties = "$projectRoot/local.properties"
    if (-not (Test-Path $localProperties)) {
        throw 'Pass -SdkRoot, or create local.properties with sdk.dir=... (see README)'
    }
    $sdkLine = Get-Content $localProperties | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
    $SdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
if (-not $JdkRoot) { throw 'Set JAVA_HOME or pass -JdkRoot so apksigner can run' }
$env:JAVA_HOME = $JdkRoot
$buildTools = Get-ChildItem "$SdkRoot/build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$aapt = Join-Path $buildTools.FullName 'aapt2.exe'
$signer = Join-Path $buildTools.FullName 'apksigner.bat'
$permissions = & $aapt dump permissions $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK permissions' }
$denied = 'android.permission.(INTERNET|ACCESS_NETWORK_STATE|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO|CAMERA|NFC)'
if (($permissions -join "`n") -match $denied) { throw 'Forbidden runtime permission found' }
$badging = & $aapt dump badging $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK metadata' }
if (($badging -join "`n") -notmatch "package: name='com.mycard.wallet'") { throw 'Wrong application ID' }
if (($badging -join "`n") -notmatch "sdkVersion:'29'") { throw 'Unexpected min SDK' }
if (($badging -join "`n") -notmatch "arm64-v8a") { throw 'Missing arm64 ABI' }
$manifest = & $aapt dump xmltree --file AndroidManifest.xml $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Cannot decode APK manifest' }
if (($manifest -join "`n") -notmatch 'android:allowBackup[^\r\n]*0x0') { throw 'Automatic backup is not disabled' }
$signature = & $signer verify --verbose --print-certs $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
& (Join-Path $buildTools.FullName 'zipalign.exe') -c -P 16 4 $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK zip alignment check failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $abis = @($zip.Entries.FullName | Where-Object { $_ -like 'lib/*/*.so' })
    $dexCount = @($zip.Entries.FullName | Where-Object { $_ -match '^classes\d*\.dex$' }).Count
} finally { $zip.Dispose() }
[ordered]@{
    apk = $apkPath
    bytes = (Get-Item -LiteralPath $apkPath).Length
    sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
    permissions = @($permissions)
    metadata = @($badging | Select-String 'package:|sdkVersion:|targetSdkVersion:|application-label:|native-code:' | ForEach-Object { $_.Line })
    automaticBackup = 'disabled'
    zipAlignment = '4-byte entries, 16-KiB native library alignment verified'
    nativeLibraries = $abis
    dexFiles = $dexCount
    signature = @($signature)
} | ConvertTo-Json -Depth 5
