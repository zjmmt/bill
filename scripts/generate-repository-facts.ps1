[CmdletBinding()]
param(
    [switch]$Check,
    [string]$RepositoryRoot = '',
    [string]$VerifiedDate = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Split-Path -Parent $PSScriptRoot
}
$repoRoot = [System.IO.Path]::GetFullPath($RepositoryRoot)
$outputPath = Join-Path $repoRoot 'docs\generated\repository-facts.md'
$settingsPath = Join-Path $repoRoot 'settings.gradle.kts'
$generatorPath = $MyInvocation.MyCommand.Path
$commonPath = Join-Path $PSScriptRoot 'repository-check-common.ps1'
. $commonPath
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Fail-Generation {
    param([string]$Message)
    Write-Host "Repository facts generation failed: $Message" -ForegroundColor Red
    exit 1
}

function Get-RelativePath {
    param([string]$Path)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $rootWithSeparator = $repoRoot.TrimEnd('\', '/') +
        [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith(
        $rootWithSeparator,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Path is outside the repository root: $fullPath"
    }
    return $fullPath.Substring($rootWithSeparator.Length).Replace('\', '/')
}

function Escape-MarkdownCell {
    param([AllowEmptyString()][string]$Value)
    if ([string]::IsNullOrEmpty($Value)) {
        return '—'
    }
    return $Value.Replace('|', '\|').Replace("`r`n", '<br>').Replace("`n", '<br>')
}

function Format-CodeCell {
    param([AllowEmptyString()][string]$Value)
    if ([string]::IsNullOrEmpty($Value)) {
        return '—'
    }
    $escaped = Escape-MarkdownCell -Value $Value.Replace('`', '\`')
    return ('`{0}`' -f $escaped)
}

function Normalize-Newlines {
    param([AllowEmptyString()][string]$Text)
    return $Text.Replace("`r`n", "`n").Replace("`r", "`n")
}

function Get-CanonicalTextHash {
    param([string]$Path)
    $content = Get-Content -Encoding UTF8 -LiteralPath $Path -Raw
    $canonicalContent = Normalize-Newlines -Text $content
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $algorithm.ComputeHash(
            [System.Text.Encoding]::UTF8.GetBytes($canonicalContent)
        )
    } finally {
        $algorithm.Dispose()
    }
    $hashText = [System.BitConverter]::ToString($hashBytes) -replace '-', ''
    return $hashText.ToLowerInvariant()
}

function Get-SourceSetDirectories {
    param([string]$ModuleRoot)
    $srcRoot = Join-Path $ModuleRoot 'src'
    if (-not (Test-Path -LiteralPath $srcRoot -PathType Container)) {
        return @()
    }
    return @(
        Get-ChildItem -LiteralPath $srcRoot -Directory |
            Sort-Object Name
    )
}

if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) {
    Fail-Generation 'settings.gradle.kts is missing.'
}

if ([string]::IsNullOrWhiteSpace($VerifiedDate)) {
    if ($Check) {
        if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
            Fail-Generation 'generated output is missing; run the generator without -Check.'
        }
        $existingOutput = Get-Content -Encoding UTF8 -LiteralPath $outputPath -Raw
        $dateMatch = [regex]::Match(
            $existingOutput,
            '(?m)^- 最后核验：(?<date>\d{4}-\d{2}-\d{2})\s*$'
        )
        if (-not $dateMatch.Success) {
            Fail-Generation 'generated output has no reusable verification date.'
        }
        $VerifiedDate = $dateMatch.Groups['date'].Value
    } else {
        $VerifiedDate = Get-Date -Format 'yyyy-MM-dd'
    }
}
if ($VerifiedDate -notmatch '^\d{4}-\d{2}-\d{2}$') {
    Fail-Generation 'VerifiedDate must use YYYY-MM-DD.'
}
try {
    [void][datetime]::ParseExact(
        $VerifiedDate,
        'yyyy-MM-dd',
        [System.Globalization.CultureInfo]::InvariantCulture
    )
} catch {
    Fail-Generation 'VerifiedDate is not a valid calendar date.'
}

$settings = Get-Content -Encoding UTF8 -LiteralPath $settingsPath -Raw
$modules = @(Get-DeclaredGradleModules -SettingsContent $settings)
if ($modules.Count -eq 0) {
    Fail-Generation 'no Gradle modules were discovered.'
}

$inputFiles = [ordered]@{}
function Add-InputFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Expected input file is missing: $Path"
    }
    $relative = Get-RelativePath -Path $Path
    $inputFiles[$relative] = [System.IO.Path]::GetFullPath($Path)
}

Add-InputFile -Path $settingsPath
$moduleRows = [System.Collections.Generic.List[object]]::new()
$moduleRoots = @{}
$moduleSet = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]$modules,
    [System.StringComparer]::Ordinal
)

foreach ($module in $modules) {
    $moduleDirectory = Get-ModuleRelativeDirectory -Module $module
    $moduleRoot = Join-Path $repoRoot $moduleDirectory
    $moduleRoots[$module] = $moduleRoot
    $buildPath = Join-Path $moduleRoot 'build.gradle.kts'
    if (-not (Test-Path -LiteralPath $buildPath -PathType Leaf)) {
        Fail-Generation "$module is declared but has no build.gradle.kts."
    }
    Add-InputFile -Path $buildPath
    $build = Get-Content -Encoding UTF8 -LiteralPath $buildPath -Raw
    $dependencyFacts = Get-GradleProjectDependencyFacts -BuildContent $build
    if ($dependencyFacts.UsesTypeSafeProjectAccessors) {
        Fail-Generation "$module uses an uninspectable type-safe project accessor."
    }
    if ($dependencyFacts.UnsupportedProjectCallCount -gt 0) {
        Fail-Generation (
            "$module has $($dependencyFacts.UnsupportedProjectCallCount) " +
            'uninspectable project() call(s).'
        )
    }
    $dependencies = @($dependencyFacts.Dependencies)
    foreach ($dependency in $dependencies) {
        if (-not $moduleSet.Contains($dependency)) {
            Fail-Generation "$module depends on undeclared module $dependency."
        }
    }
    $moduleRows.Add([pscustomobject]@{
        Module = $module
        BuildFile = Get-RelativePath -Path $buildPath
        Dependencies = $dependencies
    })
}

$schemaRoot = Join-Path $repoRoot 'data\local\schemas'
$schemaRows = [System.Collections.Generic.List[object]]::new()
if (Test-Path -LiteralPath $schemaRoot -PathType Container) {
    foreach ($databaseDirectory in Get-ChildItem -LiteralPath $schemaRoot -Directory |
        Sort-Object Name) {
        $versions = [System.Collections.Generic.List[int]]::new()
        foreach ($schemaFile in Get-ChildItem -LiteralPath $databaseDirectory.FullName `
            -Filter '*.json' -File | Sort-Object Name) {
            Add-InputFile -Path $schemaFile.FullName
            $fileVersion = 0
            if (-not [int]::TryParse(
                [System.IO.Path]::GetFileNameWithoutExtension($schemaFile.Name),
                [ref]$fileVersion
            )) {
                Fail-Generation "Room schema filename is not numeric: $($schemaFile.FullName)"
            }
            try {
                $schema = Get-Content -Encoding UTF8 -LiteralPath $schemaFile.FullName -Raw |
                    ConvertFrom-Json
                $declaredVersion = [int]$schema.database.version
            } catch {
                Fail-Generation "Room schema is unreadable: $($schemaFile.FullName)"
            }
            if ($declaredVersion -ne $fileVersion) {
                Fail-Generation (
                    "Room schema filename/version mismatch in {0}: filename {1}, document {2}." -f
                    $schemaFile.FullName,
                    $fileVersion,
                    $declaredVersion
                )
            }
            $versions.Add($fileVersion)
        }
        if ($versions.Count -gt 0) {
            $orderedVersions = @($versions | Sort-Object -Unique)
            $schemaRows.Add([pscustomobject]@{
                Database = $databaseDirectory.Name
                Versions = $orderedVersions
                Latest = $orderedVersions[-1]
            })
        }
    }
}

$androidNamespace = 'http://schemas.android.com/apk/res/android'
$toolsNamespace = 'http://schemas.android.com/tools'
$manifestRows = [System.Collections.Generic.List[object]]::new()
$componentRows = [System.Collections.Generic.List[object]]::new()
$manifestFiles = [System.Collections.Generic.List[string]]::new()

foreach ($module in $modules) {
    foreach ($sourceSet in Get-SourceSetDirectories -ModuleRoot $moduleRoots[$module]) {
        $manifestPath = Join-Path $sourceSet.FullName 'AndroidManifest.xml'
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
            continue
        }
        Add-InputFile -Path $manifestPath
        $manifestFiles.Add($manifestPath)
        $relativeManifest = Get-RelativePath -Path $manifestPath
        try {
            [xml]$manifestXml = Get-Content -Encoding UTF8 -LiteralPath $manifestPath -Raw
        } catch {
            Fail-Generation "$relativeManifest is not valid XML."
        }

        $permissionNodes = @(
            $manifestXml.SelectNodes(
                '/manifest/uses-permission | /manifest/uses-permission-sdk-23'
            )
        )
        foreach ($permissionNode in $permissionNodes) {
            $manifestRows.Add([pscustomobject]@{
                Manifest = $relativeManifest
                Kind = $permissionNode.LocalName
                Name = $permissionNode.GetAttribute('name', $androidNamespace)
                Operation = $permissionNode.GetAttribute('node', $toolsNamespace)
            })
        }

        $componentNodes = @(
            $manifestXml.SelectNodes(
                '/manifest/application/activity | ' +
                '/manifest/application/activity-alias | ' +
                '/manifest/application/service | ' +
                '/manifest/application/receiver | ' +
                '/manifest/application/provider'
            )
        )
        foreach ($componentNode in $componentNodes) {
            $componentRows.Add([pscustomobject]@{
                Manifest = $relativeManifest
                Kind = $componentNode.LocalName
                Name = $componentNode.GetAttribute('name', $androidNamespace)
                Exported = $componentNode.GetAttribute('exported', $androidNamespace)
                Permission = $componentNode.GetAttribute('permission', $androidNamespace)
                Operation = $componentNode.GetAttribute('node', $toolsNamespace)
            })
        }
    }
}

$testRows = [System.Collections.Generic.List[object]]::new()
$testFiles = [System.Collections.Generic.List[string]]::new()
foreach ($module in $modules) {
    foreach ($suite in @(
        [pscustomobject]@{ Directory = 'test'; Label = 'JVM' },
        [pscustomobject]@{ Directory = 'androidTest'; Label = 'Android' }
    )) {
        $suiteRoot = Join-Path (Join-Path $moduleRoots[$module] 'src') $suite.Directory
        if (-not (Test-Path -LiteralPath $suiteRoot -PathType Container)) {
            continue
        }
        $suiteFiles = @(
            Get-ChildItem -LiteralPath $suiteRoot -Recurse -File |
                Where-Object { $_.Extension -in @('.kt', '.java') } |
                Sort-Object FullName
        )
        $annotationCount = 0
        foreach ($testFile in $suiteFiles) {
            Add-InputFile -Path $testFile.FullName
            $testFiles.Add($testFile.FullName)
            $rawContent = Get-Content `
                -Encoding UTF8 `
                -LiteralPath $testFile.FullName `
                -Raw
            $content = Remove-KotlinStyleComments `
                -Content $rawContent `
                -MaskStrings
            $annotationCount += [regex]::Matches(
                $content,
                '@(?:org\.junit(?:\.jupiter\.api)?\.)?Test\b'
            ).Count
        }
        if ($suiteFiles.Count -gt 0) {
            $testRows.Add([pscustomobject]@{
                Module = $module
                Suite = $suite.Label
                Files = $suiteFiles.Count
                TestAnnotations = $annotationCount
            })
        }
    }
}

$hashRecords = [System.Collections.Generic.List[string]]::new()
$generatorHash = Get-CanonicalTextHash -Path $generatorPath
$commonHash = Get-CanonicalTextHash -Path $commonPath
$hashRecords.Add("@generator|$generatorHash")
$hashRecords.Add("@shared-parser|$commonHash")
foreach ($relative in @($inputFiles.Keys | Sort-Object)) {
    $fileHash = Get-CanonicalTextHash -Path $inputFiles[$relative]
    $hashRecords.Add("$relative|$fileHash")
}
$digestSource = [string]::Join("`n", $hashRecords)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $digestBytes = $sha256.ComputeHash(
        [System.Text.Encoding]::UTF8.GetBytes($digestSource)
    )
} finally {
    $sha256.Dispose()
}
$inputDigestText = [System.BitConverter]::ToString($digestBytes) -replace '-', ''
$inputDigest = $inputDigestText.ToLowerInvariant()

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# 仓库生成事实')
$lines.Add('')
$lines.Add('- 状态：自动生成')
$lines.Add('- 所有者：`scripts/generate-repository-facts.ps1`')
$lines.Add("- 最后核验：$VerifiedDate")
$lines.Add('- 事实来源：`settings.gradle.kts`、模块 `build.gradle.kts`、Room schema、源 Manifest 与测试源码')
$lines.Add('- 生成命令：`cmd.exe /d /s /c powershell -NoProfile -ExecutionPolicy Bypass -File scripts\generate-repository-facts.ps1`')
$lines.Add('')
$lines.Add('> 自动生成，请勿手工编辑。测试数字只表示源码中发现的文件和 `@Test` 注解，不代表测试已经执行或通过。')
$lines.Add('')
$lines.Add('## 输入摘要')
$lines.Add('')
$lines.Add("| 项目 | 当前值 |")
$lines.Add("|---|---:|")
$lines.Add("| Gradle 模块 | $($modules.Count) |")
$lines.Add("| 源 Manifest | $($manifestFiles.Count) |")
$lines.Add("| 测试源码文件 | $($testFiles.Count) |")
$lines.Add("| 生成输入文件 | $($inputFiles.Count) |")
$lines.Add(('| 输入 SHA-256 | `{0}` |' -f $inputDigest))
$lines.Add('')
$lines.Add('输入摘要按 UTF-8/LF 规范化后包含生成器及共享解析库的 SHA-256；生成逻辑、源码事实或输入文件任一变化，都要求重新生成。')
$lines.Add('')
$lines.Add('## Gradle 模块依赖')
$lines.Add('')
$lines.Add('| 模块 | 构建文件 | 直接项目依赖 |')
$lines.Add('|---|---|---|')
foreach ($row in $moduleRows) {
    $dependencyText = if ($row.Dependencies.Count -eq 0) {
        '—'
    } else {
        (@($row.Dependencies | ForEach-Object { Format-CodeCell -Value $_ }) -join '<br>')
    }
    $lines.Add(
        "| $(Format-CodeCell -Value $row.Module) | " +
        "$(Format-CodeCell -Value $row.BuildFile) | $dependencyText |"
    )
}
$lines.Add('')
$lines.Add('## Room schema')
$lines.Add('')
if ($schemaRows.Count -eq 0) {
    $lines.Add('未发现 Room schema。')
} else {
    $lines.Add('| 数据库 | 已提交版本 | 最新版本 |')
    $lines.Add('|---|---|---:|')
    foreach ($row in $schemaRows) {
        $versionsText = @($row.Versions | ForEach-Object { Format-CodeCell -Value "$_" }) -join ', '
        $lines.Add(
            "| $(Format-CodeCell -Value $row.Database) | $versionsText | $($row.Latest) |"
        )
    }
}
$lines.Add('')
$lines.Add('## 源 Manifest 权限声明')
$lines.Add('')
if ($manifestRows.Count -eq 0) {
    $lines.Add('未发现权限声明。')
} else {
    $lines.Add('| Manifest | 节点 | 权限 | tools 操作 |')
    $lines.Add('|---|---|---|---|')
    foreach ($row in $manifestRows | Sort-Object Manifest, Kind, Name) {
        $lines.Add(
            "| $(Format-CodeCell -Value $row.Manifest) | " +
            "$(Format-CodeCell -Value $row.Kind) | " +
            "$(Format-CodeCell -Value $row.Name) | " +
            "$(Format-CodeCell -Value $row.Operation) |"
        )
    }
}
$lines.Add('')
$lines.Add('## 源 Manifest 组件声明')
$lines.Add('')
if ($componentRows.Count -eq 0) {
    $lines.Add('未发现组件声明。')
} else {
    $lines.Add('| Manifest | 类型 | 名称 | exported | permission | tools 操作 |')
    $lines.Add('|---|---|---|---|---|---|')
    foreach ($row in $componentRows | Sort-Object Manifest, Kind, Name) {
        $lines.Add(
            "| $(Format-CodeCell -Value $row.Manifest) | " +
            "$(Format-CodeCell -Value $row.Kind) | " +
            "$(Format-CodeCell -Value $row.Name) | " +
            "$(Format-CodeCell -Value $row.Exported) | " +
            "$(Format-CodeCell -Value $row.Permission) | " +
            "$(Format-CodeCell -Value $row.Operation) |"
        )
    }
}
$lines.Add('')
$lines.Add('## 测试源码清单')
$lines.Add('')
if ($testRows.Count -eq 0) {
    $lines.Add('未发现 `src/test` 或 `src/androidTest` Kotlin/Java 测试源码。')
} else {
    $lines.Add('| 模块 | 测试集 | 源码文件 | `@Test` 注解 |')
    $lines.Add('|---|---|---:|---:|')
    foreach ($row in $testRows | Sort-Object Module, Suite) {
        $lines.Add(
            "| $(Format-CodeCell -Value $row.Module) | $($row.Suite) | " +
            "$($row.Files) | $($row.TestAnnotations) |"
        )
    }
    $lines.Add(
        "| 合计 | — | $($testFiles.Count) | " +
        "$(@($testRows | Measure-Object -Property TestAnnotations -Sum).Sum) |"
    )
}
$lines.Add('')
$lines.Add('计数口径：仅扫描各模块的 `src/test` 与 `src/androidTest` 下 `.kt`/`.java` 文件，并统计 `@Test` 或完全限定的 JUnit `@Test` 注解；参数化测试等其他注解不计入。')
$lines.Add('')

$rendered = [string]::Join("`n", $lines)
if ($Check) {
    $committed = Normalize-Newlines -Text (
        Get-Content -Encoding UTF8 -LiteralPath $outputPath -Raw
    )
    if ($committed -cne $rendered) {
        Write-Host (
            'Generated repository facts are stale. Run ' +
            'scripts\generate-repository-facts.ps1 and commit the result.'
        ) -ForegroundColor Red
        exit 1
    }
    Write-Host (
        "Generated repository facts are current: $($inputFiles.Count) inputs, " +
        "digest $inputDigest."
    ) -ForegroundColor Green
    exit 0
}

$outputDirectory = Split-Path -Parent $outputPath
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    [void](New-Item -ItemType Directory -Path $outputDirectory)
}
[System.IO.File]::WriteAllText($outputPath, $rendered, $utf8NoBom)
Write-Host (
    "Generated $(Get-RelativePath -Path $outputPath) from $($inputFiles.Count) inputs; " +
    "digest $inputDigest."
) -ForegroundColor Green
