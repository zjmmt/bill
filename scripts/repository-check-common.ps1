function Remove-KotlinStyleComments {
    param(
        [AllowEmptyString()][string]$Content,
        [switch]$MaskStrings
    )

    $builder = New-Object System.Text.StringBuilder
    $state = 'normal'
    $blockDepth = 0
    $index = 0

    while ($index -lt $Content.Length) {
        $current = $Content[$index]
        $next = if ($index + 1 -lt $Content.Length) {
            $Content[$index + 1]
        } else {
            [char]0
        }
        $third = if ($index + 2 -lt $Content.Length) {
            $Content[$index + 2]
        } else {
            [char]0
        }

        switch ($state) {
            'normal' {
                if ($current -eq '/' -and $next -eq '/') {
                    [void]$builder.Append('  ')
                    $index += 2
                    $state = 'line-comment'
                    continue
                }
                if ($current -eq '/' -and $next -eq '*') {
                    [void]$builder.Append('  ')
                    $index += 2
                    $blockDepth = 1
                    $state = 'block-comment'
                    continue
                }
                if ($current -eq '"' -and $next -eq '"' -and $third -eq '"') {
                    if ($MaskStrings) {
                        [void]$builder.Append('   ')
                    } else {
                        [void]$builder.Append('"""')
                    }
                    $index += 3
                    $state = 'triple-string'
                    continue
                }
                if ($current -eq '"') {
                    if ($MaskStrings) {
                        [void]$builder.Append(' ')
                    } else {
                        [void]$builder.Append($current)
                    }
                    $index++
                    $state = 'double-string'
                    continue
                }
                if ($current -eq "'") {
                    if ($MaskStrings) {
                        [void]$builder.Append(' ')
                    } else {
                        [void]$builder.Append($current)
                    }
                    $index++
                    $state = 'single-string'
                    continue
                }
                [void]$builder.Append($current)
                $index++
                continue
            }
            'line-comment' {
                if ($current -eq "`r" -or $current -eq "`n") {
                    [void]$builder.Append($current)
                    $state = 'normal'
                } else {
                    [void]$builder.Append(' ')
                }
                $index++
                continue
            }
            'block-comment' {
                if ($current -eq '/' -and $next -eq '*') {
                    [void]$builder.Append('  ')
                    $index += 2
                    $blockDepth++
                    continue
                }
                if ($current -eq '*' -and $next -eq '/') {
                    [void]$builder.Append('  ')
                    $index += 2
                    $blockDepth--
                    if ($blockDepth -eq 0) {
                        $state = 'normal'
                    }
                    continue
                }
                if ($current -eq "`r" -or $current -eq "`n") {
                    [void]$builder.Append($current)
                } else {
                    [void]$builder.Append(' ')
                }
                $index++
                continue
            }
            'double-string' {
                if ($current -eq '\' -and $index + 1 -lt $Content.Length) {
                    if ($MaskStrings) {
                        [void]$builder.Append('  ')
                    } else {
                        [void]$builder.Append($current)
                        [void]$builder.Append($Content[$index + 1])
                    }
                    $index += 2
                    continue
                }
                if ($current -eq '"') {
                    if ($MaskStrings) {
                        [void]$builder.Append(' ')
                    } else {
                        [void]$builder.Append($current)
                    }
                    $index++
                    $state = 'normal'
                    continue
                }
                if (
                    $MaskStrings -and
                    ($current -eq "`r" -or $current -eq "`n")
                ) {
                    [void]$builder.Append($current)
                } elseif ($MaskStrings) {
                    [void]$builder.Append(' ')
                } else {
                    [void]$builder.Append($current)
                }
                $index++
                continue
            }
            'single-string' {
                if ($current -eq '\' -and $index + 1 -lt $Content.Length) {
                    if ($MaskStrings) {
                        [void]$builder.Append('  ')
                    } else {
                        [void]$builder.Append($current)
                        [void]$builder.Append($Content[$index + 1])
                    }
                    $index += 2
                    continue
                }
                if ($current -eq "'") {
                    if ($MaskStrings) {
                        [void]$builder.Append(' ')
                    } else {
                        [void]$builder.Append($current)
                    }
                    $index++
                    $state = 'normal'
                    continue
                }
                if (
                    $MaskStrings -and
                    ($current -eq "`r" -or $current -eq "`n")
                ) {
                    [void]$builder.Append($current)
                } elseif ($MaskStrings) {
                    [void]$builder.Append(' ')
                } else {
                    [void]$builder.Append($current)
                }
                $index++
                continue
            }
            'triple-string' {
                if ($current -eq '"' -and $next -eq '"' -and $third -eq '"') {
                    if ($MaskStrings) {
                        [void]$builder.Append('   ')
                    } else {
                        [void]$builder.Append('"""')
                    }
                    $index += 3
                    $state = 'normal'
                    continue
                }
                if (
                    $MaskStrings -and
                    ($current -eq "`r" -or $current -eq "`n")
                ) {
                    [void]$builder.Append($current)
                } elseif ($MaskStrings) {
                    [void]$builder.Append(' ')
                } else {
                    [void]$builder.Append($current)
                }
                $index++
                continue
            }
        }
    }

    return $builder.ToString()
}

function Get-DeclaredGradleModules {
    param([string]$SettingsContent)

    return @(
        [regex]::Matches(
            $SettingsContent,
            '(?m)^\s*"(?<module>:[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*)",?\s*$'
        ) |
            ForEach-Object { $_.Groups['module'].Value } |
            Sort-Object -Unique
    )
}

function Get-ModuleRelativeDirectory {
    param([string]$Module)
    return $Module.TrimStart(':').Replace(':', '\')
}

function Get-GradleProjectDependencyFacts {
    param([AllowEmptyString()][string]$BuildContent)

    $inspectableContent = Remove-KotlinStyleComments -Content $BuildContent
    $projectCallPattern = '(?<![A-Za-z0-9_])project\s*\('
    $dependencyPattern =
        '(?<![A-Za-z0-9_])project\s*\(\s*(?:path\s*=\s*)?' +
        '"(?<dependency>:[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*)"\s*\)'
    $projectCalls = @([regex]::Matches($inspectableContent, $projectCallPattern))
    $dependencyMatches = @([regex]::Matches($inspectableContent, $dependencyPattern))

    return [pscustomobject]@{
        ContentWithoutComments = $inspectableContent
        Dependencies = @(
            $dependencyMatches |
                ForEach-Object { $_.Groups['dependency'].Value } |
                Sort-Object -Unique
        )
        ProjectCallCount = $projectCalls.Count
        UnsupportedProjectCallCount = $projectCalls.Count - $dependencyMatches.Count
        UsesTypeSafeProjectAccessors = $inspectableContent -match '\bprojects\.'
    }
}

function Get-ProductionSourceSetDirectories {
    param([string]$ModuleRoot)

    $srcRoot = Join-Path $ModuleRoot 'src'
    if (-not (Test-Path -LiteralPath $srcRoot -PathType Container)) {
        return @()
    }
    return @(
        Get-ChildItem -LiteralPath $srcRoot -Directory |
            Where-Object {
                $_.Name -notmatch '^(?:test|androidTest|testFixtures)(?:$|[A-Z0-9_-])'
            } |
            Sort-Object Name
    )
}
