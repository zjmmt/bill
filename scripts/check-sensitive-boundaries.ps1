[CmdletBinding()]
param(
    [string]$RepositoryRoot = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Split-Path -Parent $PSScriptRoot
}
. (Join-Path $PSScriptRoot 'repository-check-common.ps1')
$repoRoot = [System.IO.Path]::GetFullPath($RepositoryRoot)
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param([string]$Message)
    $failures.Add($Message)
}

$loggingPatterns = [ordered]@{
    'android.util.Log' = '\bandroid\.util\.Log\b'
    'Log method' = '(?<![A-Za-z0-9_])Log\.(?:v|d|i|w|e|wtf)\s*\('
    'Timber' = '\bTimber\.'
    'console print' = '(?<![A-Za-z0-9_.])print(?:ln)?\s*\('
    'kotlin.io print' = '\bkotlin\.io\.print(?:ln)?\s*\('
    'System stream' = '\bSystem\.(?:out|err)\b'
    'stack trace print' = '\.printStackTrace\s*\('
    'java.util.logging' = '\bjava\.util\.logging\b'
}

$settingsPath = Join-Path $repoRoot 'settings.gradle.kts'
if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) {
    Write-Host 'Sensitive-boundary checks failed: settings.gradle.kts is missing.' `
        -ForegroundColor Red
    exit 1
}
$settings = Get-Content -Encoding UTF8 -LiteralPath $settingsPath -Raw
$modules = @(Get-DeclaredGradleModules -SettingsContent $settings)
if ($modules.Count -eq 0) {
    Write-Host 'Sensitive-boundary checks failed: no Gradle modules were discovered.' `
        -ForegroundColor Red
    exit 1
}
$moduleRoots = @{}
$productionSourceList = [System.Collections.Generic.List[object]]::new()
$manifestList = [System.Collections.Generic.List[object]]::new()
foreach ($module in $modules) {
    $moduleRoot = Join-Path $repoRoot (
        Get-ModuleRelativeDirectory -Module $module
    )
    $moduleRoots[$module] = $moduleRoot
    foreach ($sourceSet in Get-ProductionSourceSetDirectories -ModuleRoot $moduleRoot) {
        foreach ($source in Get-ChildItem -LiteralPath $sourceSet.FullName -Recurse -File |
            Where-Object { $_.Extension -in @('.kt', '.java') }) {
            $productionSourceList.Add($source)
        }
        $manifestPath = Join-Path $sourceSet.FullName 'AndroidManifest.xml'
        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            $manifestList.Add((Get-Item -LiteralPath $manifestPath))
        }
    }
}
$productionSources = @($productionSourceList | Sort-Object FullName -Unique)
foreach ($source in $productionSources) {
    $rawContent = Get-Content -Encoding UTF8 -LiteralPath $source.FullName -Raw
    $content = Remove-KotlinStyleComments -Content $rawContent -MaskStrings
    foreach ($entry in $loggingPatterns.GetEnumerator()) {
        foreach ($match in [regex]::Matches($content, $entry.Value)) {
            $line = [regex]::Matches($content.Substring(0, $match.Index), "`n").Count + 1
            $relative = $source.FullName.Substring($repoRoot.Length).TrimStart('\', '/')
            Add-Failure "$relative`:$line uses forbidden production output API: $($entry.Key)."
        }
    }
}

$dependencyFileMap = [ordered]@{}
foreach ($candidate in @(
    $settingsPath,
    (Join-Path $repoRoot 'build.gradle.kts'),
    (Join-Path $repoRoot 'gradle\libs.versions.toml')
)) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $dependencyFileMap[[System.IO.Path]::GetFullPath($candidate)] = $true
    }
}
foreach ($module in $modules) {
    $buildPath = Join-Path $moduleRoots[$module] 'build.gradle.kts'
    if (Test-Path -LiteralPath $buildPath -PathType Leaf) {
        $dependencyFileMap[[System.IO.Path]::GetFullPath($buildPath)] = $true
    }
}
$dependencyFiles = @(
    $dependencyFileMap.Keys |
        ForEach-Object { Get-Item -LiteralPath $_ } |
        Sort-Object FullName
)
$telemetryDependencyPatterns = [ordered]@{
    'Timber' = '(?i)\bcom\.jakewharton\.timber\b'
    'Sentry SDK' = '(?i)\bio\.sentry\b'
    'Firebase Crashlytics' = '(?i)\bcom\.google\.firebase\.crashlytics\b'
    'Firebase Analytics' = '(?i)\bcom\.google\.firebase\b[^\r\n]*firebase[-.:]analytics\b'
    'Google Analytics' = '(?i)\bcom\.google\.android\.gms\b[^\r\n]*play-services-analytics\b'
    'Datadog' = '(?i)\bcom\.datadoghq\b'
    'New Relic' = '(?i)\bcom\.newrelic\b'
    'App Center' = '(?i)\bcom\.microsoft\.appcenter\b'
    'Mixpanel' = '(?i)\bcom\.mixpanel\b'
    'Amplitude' = '(?i)\bcom\.amplitude\b'
    'OpenTelemetry' = '(?i)\bio\.opentelemetry\b'
    'Bugsnag' = '(?i)\bcom\.bugsnag\b'
    'Instabug' = '(?i)\bcom\.instabug\b'
    'Adjust' = '(?i)\bcom\.adjust\b'
    'AppsFlyer' = '(?i)\bcom\.appsflyer\b'
    'Segment' = '(?i)\bcom\.segment\.analytics\b'
    'Logback' = '(?i)\bch\.qos\.logback\b'
    'SLF4J' = '(?i)\borg\.slf4j\b'
    'Log4j' = '(?i)\borg\.apache\.logging\.log4j\b'
    'Kotlin Logging' = '(?i)\bio\.github\.oshai\b[^\r\n]*kotlin-logging\b'
}
foreach ($dependencyFile in $dependencyFiles) {
    $rawContent = Get-Content -Encoding UTF8 -LiteralPath $dependencyFile.FullName -Raw
    $content = if ($dependencyFile.Extension -eq '.kts') {
        Remove-KotlinStyleComments -Content $rawContent
    } else {
        $rawContent -replace '(?m)^\s*#.*$', ''
    }
    foreach ($entry in $telemetryDependencyPatterns.GetEnumerator()) {
        if ($content -match $entry.Value) {
            $relative = $dependencyFile.FullName.Substring($repoRoot.Length).TrimStart('\', '/')
            Add-Failure "$relative declares forbidden direct logging/telemetry dependency: $($entry.Key)."
        }
    }
}

$highRiskPermissions = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]@(
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.READ_SMS',
        'android.permission.RECEIVE_SMS',
        'android.permission.SEND_SMS',
        'android.permission.READ_CALENDAR',
        'android.permission.WRITE_CALENDAR',
        'android.permission.CAMERA',
        'android.permission.WRITE_CONTACTS',
        'android.permission.GET_ACCOUNTS',
        'android.permission.READ_CALL_LOG',
        'android.permission.WRITE_CALL_LOG',
        'android.permission.READ_PHONE_STATE',
        'android.permission.READ_PHONE_NUMBERS',
        'android.permission.CALL_PHONE',
        'android.permission.ANSWER_PHONE_CALLS',
        'android.permission.ADD_VOICEMAIL',
        'android.permission.USE_SIP',
        'android.permission.PROCESS_OUTGOING_CALLS',
        'android.permission.READ_CONTACTS',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_BACKGROUND_LOCATION',
        'android.permission.RECORD_AUDIO',
        'android.permission.BODY_SENSORS',
        'android.permission.BODY_SENSORS_BACKGROUND',
        'android.permission.ACTIVITY_RECOGNITION',
        'android.permission.BLUETOOTH_SCAN',
        'android.permission.BLUETOOTH_CONNECT',
        'android.permission.BLUETOOTH_ADVERTISE',
        'android.permission.NEARBY_WIFI_DEVICES',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.READ_EXTERNAL_STORAGE',
        'android.permission.WRITE_EXTERNAL_STORAGE',
        'android.permission.MANAGE_EXTERNAL_STORAGE',
        'android.permission.READ_MEDIA_IMAGES',
        'android.permission.READ_MEDIA_VIDEO',
        'android.permission.READ_MEDIA_AUDIO',
        'android.permission.QUERY_ALL_PACKAGES',
        'android.permission.PACKAGE_USAGE_STATS',
        'android.permission.REQUEST_INSTALL_PACKAGES',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.WRITE_SETTINGS'
    ),
    [System.StringComparer]::Ordinal
)
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$toolsNamespace = 'http://schemas.android.com/tools'
$manifests = @($manifestList | Sort-Object FullName -Unique)
foreach ($manifest in $manifests) {
    try {
        [xml]$xml = Get-Content -Encoding UTF8 -LiteralPath $manifest.FullName -Raw
        $permissionNodes = @(
            $xml.SelectNodes('/manifest/uses-permission | /manifest/uses-permission-sdk-23')
        )
        foreach ($node in $permissionNodes) {
            $name = $node.GetAttribute('name', $androidNamespace)
            $operation = $node.GetAttribute('node', $toolsNamespace)
            if ($highRiskPermissions.Contains($name) -and $operation -ne 'remove') {
                $relative = $manifest.FullName.Substring($repoRoot.Length).TrimStart('\', '/')
                Add-Failure "$relative adds high-risk permission $name without an approved boundary change."
            }
        }
    } catch {
        $relative = $manifest.FullName.Substring($repoRoot.Length).TrimStart('\', '/')
        Add-Failure "$relative is not valid XML: $($_.Exception.Message)"
    }
}

if ($failures.Count -gt 0) {
    Write-Host "Sensitive-boundary checks failed with $($failures.Count) issue(s):" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "- $failure" -ForegroundColor Red
    }
    exit 1
}

$successMessage = (
    "Sensitive-boundary checks passed: {0} production source files, {1} dependency files, " +
    '{2} source manifests; no direct logging, telemetry dependency, or added high-risk permission.'
) -f $productionSources.Count, $dependencyFiles.Count, $manifests.Count
Write-Host $successMessage -ForegroundColor Green
