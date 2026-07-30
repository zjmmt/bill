[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$architectureChecker = Join-Path $PSScriptRoot 'check-architecture.ps1'
$sensitiveChecker = Join-Path $PSScriptRoot 'check-sensitive-boundaries.ps1'
$factsGenerator = Join-Path $PSScriptRoot 'generate-repository-facts.ps1'
$powerShellPath = (Get-Process -Id $PID).Path
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$failures = [System.Collections.Generic.List[string]]::new()

function Write-FixtureFile {
    param(
        [string]$Root,
        [string]$RelativePath,
        [string]$Content
    )
    $path = Join-Path $Root $RelativePath
    $directory = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $directory -Force)
    }
    [System.IO.File]::WriteAllText($path, $Content, $utf8NoBom)
}

function Invoke-RepositoryScript {
    param(
        [string]$Script,
        [string]$Root,
        [string[]]$ExtraArguments = @()
    )
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $Script,
        '-RepositoryRoot',
        $Root
    ) + $ExtraArguments
    & $powerShellPath @arguments *> $null
    return $LASTEXITCODE
}

function Expect-ExitCode {
    param(
        [string]$Name,
        [int]$Expected,
        [int]$Actual
    )
    if ($Expected -ne $Actual) {
        $failures.Add("$Name expected exit $Expected but received $Actual.")
    }
}

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$tempBaseWithSeparator = $tempBase.TrimEnd('\', '/') +
    [System.IO.Path]::DirectorySeparatorChar
$fixtureRoot = Join-Path $tempBase (
    'bill-repository-checks-' + [guid]::NewGuid().ToString('N')
)

try {
    [void](New-Item -ItemType Directory -Path $fixtureRoot)
    Write-FixtureFile -Root $fixtureRoot -RelativePath 'settings.gradle.kts' -Content @'
include(
    ":app",
    ":core:model",
    ":data:local",
    ":source:contract",
)
'@
    Write-FixtureFile -Root $fixtureRoot -RelativePath 'app\build.gradle.kts' -Content @'
plugins {
    id("com.android.application")
}
dependencies {
    implementation(project(":source:contract"))
    // implementation("io.sentry:sentry-android:0.0.0")
}
'@
    Write-FixtureFile -Root $fixtureRoot -RelativePath 'core\model\build.gradle.kts' -Content @'
plugins {
    kotlin("jvm")
}
'@
    Write-FixtureFile -Root $fixtureRoot -RelativePath 'data\local\build.gradle.kts' -Content @'
plugins {
    id("com.android.library")
}
'@
    $validSourceBuild = @'
plugins {
    kotlin("jvm")
}
dependencies {
    implementation(project(":core:model"))
    // implementation(project(":app"))
}
'@
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'source\contract\build.gradle.kts' `
        -Content $validSourceBuild
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\src\main\kotlin\Fixture.kt' `
        -Content (
            "package fixture`n`n" +
            "// android.util.Log.d(`"fixture`", `"comment only`")`n" +
            "val documentation = `"android.util.Log.d and println are text only`"`n" +
            "fun value(): Int = 1`n"
        )
    $removedPermissionManifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission
        android:name="android.permission.INTERNET"
        tools:node="remove" />
</manifest>
'@
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\src\main\AndroidManifest.xml' `
        -Content $removedPermissionManifest
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\src\test\kotlin\FixtureTest.kt' `
        -Content "package fixture`n`n@Test fun fixtureTest() = Unit`n"

    Expect-ExitCode -Name 'architecture positive control' -Expected 0 -Actual (
        Invoke-RepositoryScript -Script $architectureChecker -Root $fixtureRoot
    )
    Expect-ExitCode -Name 'sensitive-boundary positive control' -Expected 0 -Actual (
        Invoke-RepositoryScript -Script $sensitiveChecker -Root $fixtureRoot
    )

    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'source\contract\build.gradle.kts' `
        -Content "dependencies { implementation(project(path = `":data:local`")) }`n"
    $architectureNegativeExit = Invoke-RepositoryScript `
        -Script $architectureChecker `
        -Root $fixtureRoot
    if ($architectureNegativeExit -eq 0) {
        $failures.Add('architecture negative control did not reject source -> data:local.')
    }
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'source\contract\build.gradle.kts' `
        -Content $validSourceBuild

    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'source\contract\build.gradle.kts' `
        -Content @'
dependencies {
    implementation(project(mapOf("path" to ":app")))
}
'@
    $unsupportedProjectExit = Invoke-RepositoryScript `
        -Script $architectureChecker `
        -Root $fixtureRoot
    if ($unsupportedProjectExit -eq 0) {
        $failures.Add('architecture negative control accepted an uninspectable project() call.')
    }
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'source\contract\build.gradle.kts' `
        -Content $validSourceBuild

    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\src\main\kotlin\Fixture.kt' `
        -Content (
            "package fixture`n`nfun value(): Int {`n" +
            "    android.util.Log.d(`"fixture`", `"value`")`n" +
            "    return 1`n}`n"
        )
    $loggingNegativeExit = Invoke-RepositoryScript `
        -Script $sensitiveChecker `
        -Root $fixtureRoot
    if ($loggingNegativeExit -eq 0) {
        $failures.Add('sensitive-boundary negative control did not reject direct logging.')
    }
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\src\main\kotlin\Fixture.kt' `
        -Content (
            "package fixture`n`n" +
            "// android.util.Log.d(`"fixture`", `"comment only`")`n" +
            "val documentation = `"android.util.Log.d and println are text only`"`n" +
            "fun value(): Int = 1`n"
        )

    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\build.gradle.kts' `
        -Content @'
plugins {
    id("com.android.application")
}
dependencies {
    implementation(project(":source:contract"))
    implementation("io.sentry:sentry-android:0.0.0")
}
'@
    $telemetryNegativeExit = Invoke-RepositoryScript `
        -Script $sensitiveChecker `
        -Root $fixtureRoot
    if ($telemetryNegativeExit -eq 0) {
        $failures.Add(
            'sensitive-boundary negative control did not reject a telemetry dependency.'
        )
    }
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\build.gradle.kts' `
        -Content @'
plugins {
    id("com.android.application")
}
dependencies {
    implementation(project(":source:contract"))
    // implementation("io.sentry:sentry-android:0.0.0")
}
'@

    $addedPermissionManifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
'@
    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\src\providerResearch\AndroidManifest.xml' `
        -Content $addedPermissionManifest
    $permissionNegativeExit = Invoke-RepositoryScript `
        -Script $sensitiveChecker `
        -Root $fixtureRoot
    if ($permissionNegativeExit -eq 0) {
        $failures.Add(
            'sensitive-boundary negative control did not reject a flavor INTERNET permission.'
        )
    }

    $generatorArguments = @('-VerifiedDate', '2000-01-01')
    Expect-ExitCode -Name 'facts generator positive control' -Expected 0 -Actual (
        Invoke-RepositoryScript `
            -Script $factsGenerator `
            -Root $fixtureRoot `
            -ExtraArguments $generatorArguments
    )
    $generatedPath = Join-Path $fixtureRoot 'docs\generated\repository-facts.md'
    $firstHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $generatedPath).Hash
    Expect-ExitCode -Name 'facts generator repeat' -Expected 0 -Actual (
        Invoke-RepositoryScript `
            -Script $factsGenerator `
            -Root $fixtureRoot `
            -ExtraArguments $generatorArguments
    )
    $secondHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $generatedPath).Hash
    if ($firstHash -cne $secondHash) {
        $failures.Add('facts generator was not byte-stable for unchanged inputs.')
    }
    Expect-ExitCode -Name 'facts freshness positive control' -Expected 0 -Actual (
        Invoke-RepositoryScript `
            -Script $factsGenerator `
            -Root $fixtureRoot `
            -ExtraArguments @('-Check', '-VerifiedDate', '2000-01-01')
    )

    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\src\main\kotlin\Fixture.kt' `
        -Content (
            "package fixture`r`n`r`n" +
            "// android.util.Log.d(`"fixture`", `"comment only`")`r`n" +
            "val documentation = `"android.util.Log.d and println are text only`"`r`n" +
            "fun value(): Int = 1`r`n"
        )
    Expect-ExitCode -Name 'facts newline normalization control' -Expected 0 -Actual (
        Invoke-RepositoryScript `
            -Script $factsGenerator `
            -Root $fixtureRoot `
            -ExtraArguments @('-Check', '-VerifiedDate', '2000-01-01')
    )

    Write-FixtureFile -Root $fixtureRoot `
        -RelativePath 'app\build.gradle.kts' `
        -Content @'
plugins {
    id("com.android.application")
}
dependencies {
    implementation(project(":source:contract"))
    implementation(project(":core:model"))
}
'@
    $staleFactsExit = Invoke-RepositoryScript `
        -Script $factsGenerator `
        -Root $fixtureRoot `
        -ExtraArguments @('-Check', '-VerifiedDate', '2000-01-01')
    if ($staleFactsExit -eq 0) {
        $failures.Add('facts freshness negative control accepted changed inputs.')
    }
} finally {
    $resolvedFixture = [System.IO.Path]::GetFullPath($fixtureRoot)
    $safeToDelete = (
        $resolvedFixture.StartsWith(
            $tempBaseWithSeparator,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -and
        $resolvedFixture -ne $tempBase
    )
    if (-not $safeToDelete) {
        throw "Refusing to remove unsafe fixture path: $resolvedFixture"
    }
    if (Test-Path -LiteralPath $resolvedFixture) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}

if ($failures.Count -gt 0) {
    Write-Host "Repository-check self-tests failed with $($failures.Count) issue(s):" `
        -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "- $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host (
    'Repository-check self-tests passed: allowed fixture accepted; architecture, logging, ' +
    'telemetry, permission, stale-output negatives rejected; generator byte/newline stability ' +
    'confirmed.'
) -ForegroundColor Green
