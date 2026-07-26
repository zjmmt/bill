[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param([string]$Message)
    $failures.Add($Message)
}

$requiredFiles = @(
    'README.md',
    'AGENTS.md',
    'ARCHITECTURE.md',
    'docs/index.md',
    'docs/PRODUCT_SENSE.md',
    'docs/DESIGN.md',
    'docs/FRONTEND.md',
    'docs/SECURITY.md',
    'docs/RELIABILITY.md',
    'docs/QUALITY_SCORE.md',
    'docs/PLANS.md',
    'docs/GLOSSARY.md',
    'docs/product-specs/index.md',
    'docs/product-specs/mvp.md',
    'docs/product-specs/source-coverage.md',
    'docs/design-docs/index.md',
    'docs/design-docs/domain-model.md',
    'docs/design-docs/ingestion-and-source-adapters.md',
    'docs/design-docs/reconciliation.md',
    'docs/decisions/index.md',
    'docs/exec-plans/index.md',
    'docs/exec-plans/tech-debt-tracker.md',
    'docs/generated/README.md',
    'docs/references/index.md'
)

foreach ($relativePath in $requiredFiles) {
    $fullPath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        Add-Failure "Missing required file: $relativePath"
    }
}

$agentsPath = Join-Path $repoRoot 'AGENTS.md'
if (Test-Path -LiteralPath $agentsPath -PathType Leaf) {
    $agentsLines = @(Get-Content -Encoding UTF8 -LiteralPath $agentsPath).Count
    if ($agentsLines -gt 120) {
        Add-Failure "AGENTS.md has $agentsLines lines; the navigation-map limit is 120. Move details into docs/."
    }
}

$docsRoot = Join-Path $repoRoot 'docs'
$metadataFiles = @()
if (Test-Path -LiteralPath $docsRoot -PathType Container) {
    $metadataFiles += Get-ChildItem -LiteralPath $docsRoot -Recurse -Filter '*.md' -File
}
$architecturePath = Join-Path $repoRoot 'ARCHITECTURE.md'
if (Test-Path -LiteralPath $architecturePath -PathType Leaf) {
    $metadataFiles += Get-Item -LiteralPath $architecturePath
}

foreach ($file in $metadataFiles) {
    $content = Get-Content -Encoding UTF8 -LiteralPath $file.FullName -Raw
    $relative = $file.FullName.Substring($repoRoot.Length).TrimStart('\', '/')

    $metadataFields = @{
        'status' = '\u72B6\u6001'
        'owner' = '\u6240\u6709\u8005'
        'last_verified' = '\u6700\u540E\u6838\u9A8C'
        'source_of_truth' = '\u4E8B\u5B9E\u6765\u6E90'
    }
    foreach ($fieldName in $metadataFields.Keys) {
        $fieldPattern = $metadataFields[$fieldName]
        if ($content -notmatch "(?m)^\- $fieldPattern\uFF1A.+$") {
            Add-Failure "$relative is missing metadata field: $fieldName"
        }
    }

    if ($content -match '(?m)^\- \u6700\u540E\u6838\u9A8C\uFF1A(?!\d{4}-\d{2}-\d{2}).+$') {
        Add-Failure "$relative has a last_verified date that is not YYYY-MM-DD."
    }
}

$markdownFiles = @()
foreach ($rootFile in @('README.md', 'AGENTS.md', 'ARCHITECTURE.md')) {
    $path = Join-Path $repoRoot $rootFile
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $markdownFiles += Get-Item -LiteralPath $path
    }
}
$markdownFiles += $metadataFiles | Where-Object { $_.FullName -ne $architecturePath }

$linkPattern = '(?<!\!)\[[^\]]+\]\(([^)]+)\)'
$linkedMarkdownTargets = [System.Collections.Generic.HashSet[string]]::new()
foreach ($file in $markdownFiles) {
    $content = Get-Content -Encoding UTF8 -LiteralPath $file.FullName -Raw
    $relative = $file.FullName.Substring($repoRoot.Length).TrimStart('\', '/')

    foreach ($match in [regex]::Matches($content, $linkPattern)) {
        $target = $match.Groups[1].Value.Trim().Trim('<', '>')
        if ($target -match '^(https?://|mailto:|#)') {
            continue
        }

        $pathOnly = ($target -split '#', 2)[0]
        $pathOnly = ($pathOnly -split '\?', 2)[0]
        if ([string]::IsNullOrWhiteSpace($pathOnly)) {
            continue
        }

        $decodedPath = [System.Uri]::UnescapeDataString($pathOnly)
        $resolved = [System.IO.Path]::GetFullPath((Join-Path $file.DirectoryName $decodedPath))
        if (-not (Test-Path -LiteralPath $resolved)) {
            Add-Failure "$relative has a broken relative link: $target"
        } elseif ([System.IO.Path]::GetExtension($resolved) -ieq '.md') {
            $null = $linkedMarkdownTargets.Add($resolved)
        }
    }
}

$docsIndexPath = Join-Path $docsRoot 'index.md'
foreach ($file in $metadataFiles) {
    if (-not $file.FullName.StartsWith($docsRoot, [StringComparison]::OrdinalIgnoreCase)) {
        continue
    }
    if ($file.FullName -ieq $docsIndexPath) {
        continue
    }
    if (-not $linkedMarkdownTargets.Contains($file.FullName)) {
        $relative = $file.FullName.Substring($repoRoot.Length).TrimStart('\', '/')
        Add-Failure "$relative is an orphan document; link it from an index or related document."
    }
}

$scopeFiles = @(
    'README.md',
    'AGENTS.md',
    'ARCHITECTURE.md',
    'docs/PRODUCT_SENSE.md',
    'docs/product-specs/mvp.md',
    'docs/product-specs/source-coverage.md'
)
foreach ($relativePath in $scopeFiles) {
    $fullPath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        continue
    }

    $content = Get-Content -Encoding UTF8 -LiteralPath $fullPath -Raw
    $sourcePatterns = @{
        'Alipay' = '\u652F\u4ED8\u5B9D'
        'WeChat' = '\u5FAE\u4FE1'
        'bank' = '\u94F6\u884C'
    }
    foreach ($sourceName in $sourcePatterns.Keys) {
        if ($content -notmatch $sourcePatterns[$sourceName]) {
            Add-Failure "$relativePath does not mention first-class source: $sourceName"
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Host "Documentation checks failed with $($failures.Count) issue(s):" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "- $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host "Documentation checks passed: structure, metadata, relative links, AGENTS.md length, and three-source constraints are valid." -ForegroundColor Green
