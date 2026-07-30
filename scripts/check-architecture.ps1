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

$settingsPath = Join-Path $repoRoot 'settings.gradle.kts'
if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) {
    Write-Host 'Architecture check failed: settings.gradle.kts is missing.' -ForegroundColor Red
    exit 1
}

$settings = Get-Content -Encoding UTF8 -LiteralPath $settingsPath -Raw
$modules = @(Get-DeclaredGradleModules -SettingsContent $settings)
if ($modules.Count -eq 0) {
    Write-Host 'Architecture check failed: no Gradle modules were discovered.' -ForegroundColor Red
    exit 1
}

$moduleSet = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]$modules,
    [System.StringComparer]::Ordinal
)
$sourceModules = @($modules | Where-Object { $_ -like ':source:*' })
$allowedDependencies = @{}

$allowedDependencies[':app'] = @($modules | Where-Object { $_ -ne ':app' })
$allowedDependencies[':application'] = @(
    ':core:model',
    ':core:domain',
    ':core:ledger'
) + $sourceModules
$allowedDependencies[':core:designsystem'] = @(':core:model')
$allowedDependencies[':core:domain'] = @(':core:model')
$allowedDependencies[':core:ledger'] = @(':core:model', ':core:domain')
$allowedDependencies[':core:model'] = @()
$allowedDependencies[':data:local'] = @(
    ':core:model',
    ':core:domain',
    ':core:ledger',
    ':source:contract',
    ':source:pipeline',
    ':source:review-contract'
)
$allowedDependencies[':ocr:paddle'] = @()

foreach ($module in $modules | Where-Object { $_ -like ':feature:*' }) {
    $allowedDependencies[$module] = @(
        ':application',
        ':core:designsystem',
        ':core:model'
    )
}
foreach ($module in $sourceModules) {
    $allowedDependencies[$module] = switch ($module) {
        ':source:contract' { @(':core:model') }
        ':source:pipeline' { @(':source:contract') }
        ':source:review-contract' { @(':core:domain', ':source:contract') }
        default { @(':source:contract') }
    }
}

$graph = @{}
$jvmOnlyModules = @(
    ':application',
    ':core:domain',
    ':core:ledger',
    ':core:model'
) + $sourceModules

foreach ($module in $modules) {
    $moduleDirectory = Get-ModuleRelativeDirectory -Module $module
    $buildPath = Join-Path (Join-Path $repoRoot $moduleDirectory) 'build.gradle.kts'
    if (-not (Test-Path -LiteralPath $buildPath -PathType Leaf)) {
        Add-Failure "$module is declared but has no build.gradle.kts."
        $graph[$module] = @()
        continue
    }
    if (-not $allowedDependencies.ContainsKey($module)) {
        Add-Failure "$module has no architecture rule. Add an explicit dependency boundary."
    }

    $build = Get-Content -Encoding UTF8 -LiteralPath $buildPath -Raw
    $dependencyFacts = Get-GradleProjectDependencyFacts -BuildContent $build
    if ($dependencyFacts.UsesTypeSafeProjectAccessors) {
        Add-Failure "$module uses a type-safe project accessor that this checker cannot inspect."
    }
    if ($dependencyFacts.UnsupportedProjectCallCount -gt 0) {
        Add-Failure (
            "$module has $($dependencyFacts.UnsupportedProjectCallCount) project() call(s) " +
            'that this checker cannot inspect.'
        )
    }
    if (
        $module -in $jvmOnlyModules -and
        $dependencyFacts.ContentWithoutComments -match '\bcom\.android\.'
    ) {
        Add-Failure "$module must remain Android-free but applies an Android Gradle plugin."
    }

    $dependencies = @($dependencyFacts.Dependencies)
    $graph[$module] = $dependencies

    foreach ($dependency in $dependencies) {
        if (-not $moduleSet.Contains($dependency)) {
            Add-Failure "$module depends on undeclared module $dependency."
            continue
        }
        $allowed = @($allowedDependencies[$module])
        if ($dependency -notin $allowed) {
            Add-Failure "$module -> $dependency violates the declared dependency direction."
        }
    }

    if ($module -in $jvmOnlyModules) {
        $sourceRoot = Join-Path (Split-Path -Parent $buildPath) 'src'
        if (Test-Path -LiteralPath $sourceRoot -PathType Container) {
            foreach ($sourceFile in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File |
                Where-Object { $_.Extension -in @('.kt', '.java') }) {
                $rawContent = Get-Content `
                    -Encoding UTF8 `
                    -LiteralPath $sourceFile.FullName `
                    -Raw
                $content = Remove-KotlinStyleComments `
                    -Content $rawContent `
                    -MaskStrings
                if ($content -match '\bandroid(?:x)?\.') {
                    $relative = $sourceFile.FullName.Substring($repoRoot.Length)
                        .TrimStart('\', '/')
                    Add-Failure "$module references Android APIs from $relative."
                }
            }
        }
    }
}

$visitState = @{}
function Visit-Module {
    param(
        [string]$Module,
        [string[]]$Trail
    )
    if ($visitState[$Module] -eq 'visited') {
        return
    }
    if ($visitState[$Module] -eq 'visiting') {
        Add-Failure "Project dependency cycle detected: $($Trail + $Module -join ' -> ')."
        return
    }
    $visitState[$Module] = 'visiting'
    foreach ($dependency in @($graph[$Module])) {
        if ($moduleSet.Contains($dependency)) {
            Visit-Module -Module $dependency -Trail ($Trail + $Module)
        }
    }
    $visitState[$Module] = 'visited'
}

foreach ($module in $modules) {
    Visit-Module -Module $module -Trail @()
}

if ($failures.Count -gt 0) {
    Write-Host "Architecture checks failed with $($failures.Count) issue(s):" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "- $failure" -ForegroundColor Red
    }
    exit 1
}

$edgeCount = @($graph.Values | ForEach-Object { $_ }).Count
$successMessage = (
    "Architecture checks passed: {0} modules, {1} declared project dependencies, " +
    'no forbidden direction, Android leak, unknown module, or cycle.'
) -f $modules.Count, $edgeCount
Write-Host $successMessage -ForegroundColor Green
