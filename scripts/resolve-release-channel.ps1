# 判断发布标签通道，并核对标签版本与仓库项目版本一致。
# 成功时标准输出只有 stable 或 prerelease。

param(
    [Parameter(Mandatory = $true)]
    [string]$Tag,
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Split-Path -Parent $PSScriptRoot
}

$stablePattern = '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
$prereleasePattern = '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-(beta|rc)\.([1-9][0-9]*)$'

if ($Tag -match $stablePattern) {
    $channel = "stable"
} elseif ($Tag -match $prereleasePattern) {
    $channel = "prerelease"
} else {
    throw "无法识别的发布标签: $Tag"
}

$expected = $Tag.Substring(1)
$modules = @(
    "self-analyst-app",
    "self-analyst-content",
    "self-analyst-events",
    "self-analyst-file",
    "self-analyst-wiki"
)

function Read-PomVersion([string]$Path, [string]$Kind) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "缺少版本文件: $Path"
    }
    [xml]$doc = Get-Content -LiteralPath $Path -Raw -Encoding utf8
    $namespace = $doc.DocumentElement.NamespaceURI
    $scope = New-Object System.Xml.XmlNamespaceManager($doc.NameTable)
    if ([string]::IsNullOrEmpty($namespace)) {
        $prefix = ""
        $xpath = if ($Kind -eq "project") { "/project/version" } else { "/project/parent/version" }
        $node = $doc.SelectSingleNode($xpath)
    } else {
        $scope.AddNamespace("m", $namespace)
        $xpath = if ($Kind -eq "project") { "/m:project/m:version" } else { "/m:project/m:parent/m:version" }
        $node = $doc.SelectSingleNode($xpath, $scope)
    }
    if ($null -eq $node -or [string]::IsNullOrWhiteSpace($node.InnerText)) {
        throw "未读到版本: $Path"
    }
    return $node.InnerText.Trim()
}

function Read-JsonVersion([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "缺少版本文件: $Path"
    }
    $json = Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json
    $version = [string]$json.version
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw "未读到版本: $Path"
    }
    return $version.Trim()
}

function Read-CargoPackageVersion([string]$Path, [string]$PackageName) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "缺少版本文件: $Path"
    }
    $inPackage = $false
    $name = $null
    $version = $null
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        if ($line -match '^\s*\[') {
            if ($inPackage) { break }
            $inPackage = $line -match '^\s*\[package\]\s*$'
            continue
        }
        if (-not $inPackage) { continue }
        if ($line -match '^\s*name\s*=\s*"([^"]+)"\s*$') { $name = $Matches[1] }
        if ($line -match '^\s*version\s*=\s*"([^"]+)"\s*$') { $version = $Matches[1] }
    }
    if ($name -ne $PackageName -or [string]::IsNullOrWhiteSpace($version)) {
        throw "未读到 Cargo 包版本: $Path"
    }
    return $version.Trim()
}

function Read-LockPackageVersion([string]$Path, [string]$PackageName) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "缺少版本文件: $Path"
    }
    $inBlock = $false
    $name = $null
    $version = $null
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        if ($line -match '^\s*\[\[package\]\]\s*$') {
            if ($inBlock -and $name -eq $PackageName -and -not [string]::IsNullOrWhiteSpace($version)) {
                $found.Add($version.Trim())
            }
            $inBlock = $true
            $name = $null
            $version = $null
            continue
        }
        if (-not $inBlock) { continue }
        if ($line -match '^\s*name\s*=\s*"([^"]+)"\s*$') { $name = $Matches[1] }
        elseif ($line -match '^\s*version\s*=\s*"([^"]+)"\s*$' -and $null -eq $version) { $version = $Matches[1] }
    }
    if ($inBlock -and $name -eq $PackageName -and -not [string]::IsNullOrWhiteSpace($version)) {
        $found.Add($version.Trim())
    }
    if ($found.Count -ne 1) {
        throw "未读到唯一的锁文件包版本: $PackageName"
    }
    return $found[0]
}

function Assert-Version([string]$Actual, [string]$Source) {
    if ($Actual -ne $expected) {
        throw "项目版本与标签不一致: $Source 为 $Actual，标签为 $expected"
    }
}

Assert-Version (Read-PomVersion (Join-Path $RepoRoot "pom.xml") "project") "pom.xml"
foreach ($module in $modules) {
    $pom = Join-Path $RepoRoot "$module/pom.xml"
    Assert-Version (Read-PomVersion $pom "parent") "$module/pom.xml"
}
Assert-Version (Read-JsonVersion (Join-Path $RepoRoot "self-analyst-desktop/package.json")) "package.json"
Assert-Version (Read-JsonVersion (Join-Path $RepoRoot "self-analyst-desktop/src-tauri/tauri.conf.json")) "tauri.conf.json"
Assert-Version (Read-CargoPackageVersion (Join-Path $RepoRoot "self-analyst-desktop/src-tauri/Cargo.toml") "self-analyst-desktop") "self-analyst-desktop Cargo.toml"
Assert-Version (Read-LockPackageVersion (Join-Path $RepoRoot "self-analyst-desktop/src-tauri/Cargo.lock") "self-analyst-desktop") "self-analyst-desktop Cargo.lock"
Assert-Version (Read-CargoPackageVersion (Join-Path $RepoRoot "self-analyst-axsidecar/Cargo.toml") "self-analyst-axsidecar") "self-analyst-axsidecar Cargo.toml"
Assert-Version (Read-LockPackageVersion (Join-Path $RepoRoot "self-analyst-axsidecar/Cargo.lock") "self-analyst-axsidecar") "self-analyst-axsidecar Cargo.lock"

Write-Output $channel
