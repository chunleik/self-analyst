# 对打包后的可执行 JAR 做真实进程冒烟测试。
# 使用临时数据目录并关闭所有桌面采集器，不读取用户窗口、文件或既有数据库。

param(
    [string]$JarPath,
    [string]$JavaPath,
    [int]$StartupTimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = & (Join-Path $PSScriptRoot "resolve-app-jar.ps1")
} elseif (-not [System.IO.Path]::IsPathRooted($JarPath)) {
    $JarPath = Join-Path $Root $JarPath
}
$JarPath = [System.IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "缺少待验证的可执行 JAR: $JarPath"
}

if ([string]::IsNullOrWhiteSpace($JavaPath)) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin/java.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $JavaPath = $candidate
        }
    }
    if ([string]::IsNullOrWhiteSpace($JavaPath)) {
        $JavaPath = (Get-Command java -ErrorAction Stop).Source
    }
}
$JavaPath = [System.IO.Path]::GetFullPath($JavaPath)
if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
    throw "缺少 Java 运行时: $JavaPath"
}

$TempBase = [System.IO.Path]::GetFullPath((Join-Path $Root ".tmp")).TrimEnd('\')
New-Item -ItemType Directory -Path $TempBase -Force | Out-Null
$SmokeRoot = Join-Path $TempBase ("self-analyst-packaged-jar-smoke-" + [guid]::NewGuid().ToString("N"))
$SmokeRoot = [System.IO.Path]::GetFullPath($SmokeRoot)
if (-not $SmokeRoot.StartsWith($TempBase + '\', [System.StringComparison]::OrdinalIgnoreCase) -or
    -not ([System.IO.Path]::GetFileName($SmokeRoot)).StartsWith("self-analyst-packaged-jar-smoke-")) {
    throw "拒绝使用不安全的临时目录: $SmokeRoot"
}
New-Item -ItemType Directory -Path $SmokeRoot | Out-Null

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
try {
    $Port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
} finally {
    $listener.Stop()
}

$Token = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
$PortFile = Join-Path $SmokeRoot "desktop-port.txt"
$ConfigDirectory = Join-Path $SmokeRoot "data/config"
New-Item -ItemType Directory -Path $ConfigDirectory -Force | Out-Null
[System.IO.File]::WriteAllText(
    (Join-Path $ConfigDirectory "config.toml"),
    "[events]`nport = $Port`n",
    [System.Text.UTF8Encoding]::new($false))
$process = $null
$stdoutTask = $null
$stderrTask = $null
$httpHandler = [System.Net.Http.HttpClientHandler]::new()
$httpHandler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($httpHandler)
$client.Timeout = [TimeSpan]::FromSeconds(3)

function Send-SmokeRequest {
    param(
        [System.Net.Http.HttpMethod]$Method,
        [string]$Path,
        [switch]$Authenticated
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        $Method, "http://127.0.0.1:${Port}${Path}")
    try {
        if ($Authenticated) {
            $request.Headers.Add("X-SelfAnalyst-Token", $Token)
        }
        return $client.Send($request)
    } finally {
        $request.Dispose()
    }
}

try {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $JavaPath
    $info.ArgumentList.Add("-jar")
    $info.ArgumentList.Add($JarPath)
    $info.WorkingDirectory = $SmokeRoot
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    # 仅清理测试子进程继承的已移除配置，不改写用户或系统环境。
    foreach ($legacy in @('AW_MODE', 'AW_BASE_URL', 'AW_TIMEOUT', 'AW_DATA_DIR', 'AW_RAW_DIR', 'AW_RAW_QUERY_MAX_RANGE_DAYS', 'AW_RAW_QUERY_MAX_PAGE_SIZE', 'AW_RAW_LOW_DISK_WARN_BYTES', 'AW_RAW_LOW_DISK_BLOCK_BYTES', 'AW_RAW_INTEGRITY_VERIFY_ON_STARTUP', 'AW_RAW_PROJECTOR_BATCH_SIZE', 'AW_COLLECTION_WINDOW', 'AW_COLLECTION_AFK', 'AW_COLLECTION_CONTENT', 'AW_CONTENT_POLL_MS')) {
        $info.Environment.Remove($legacy) | Out-Null
    }
    $info.Environment["SELF_ANALYST_DESKTOP_TOKEN"] = $Token
    $info.Environment["SELF_ANALYST_DESKTOP_PORT_FILE"] = $PortFile
    $info.Environment["EVENTS_MODE"] = "embedded"
    $info.Environment["EVENTS_BASE_URL"] = "http://127.0.0.1:$Port/api/0"
    $info.Environment["EVENTS_DATA_DIR"] = (Join-Path $SmokeRoot "data/aw-data")
    $info.Environment["EVENTS_RAW_DIR"] = (Join-Path $SmokeRoot "data/aw-data/raw")
    $info.Environment["MEMORY_DIR"] = (Join-Path $SmokeRoot "data/memory")
    $info.Environment["EVENTS_COLLECTION_WINDOW"] = "false"
    $info.Environment["EVENTS_COLLECTION_AFK"] = "false"
    $info.Environment["EVENTS_COLLECTION_TITLE_ENABLED"] = "false"
    $info.Environment["FILE_WATCH_ENABLED"] = "false"
    $info.Environment["WIKI_ENABLED"] = "false"
    $info.Environment["EMBEDDING_ENABLED"] = "false"
    $info.Environment["WEBSEARCH_ENABLED"] = "false"

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) {
        throw "无法启动打包后的 JAR"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-Path -LiteralPath $PortFile)) {
        if ($process.HasExited) {
            throw "JAR 在发布桌面端口前退出，退出码 $($process.ExitCode)"
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not (Test-Path -LiteralPath $PortFile -PathType Leaf)) {
        throw "JAR 未在 ${StartupTimeoutSeconds}s 内发布桌面端口文件"
    }
    $PublishedPort = (Get-Content -LiteralPath $PortFile -Raw).Trim()
    if ($PublishedPort -ne $Port.ToString()) {
        throw "桌面端口文件为 $PublishedPort，预期为 $Port"
    }

    $ready = $false
    $lastProbe = "尚未连接"
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            throw "JAR 在健康检查前退出，退出码 $($process.ExitCode)"
        }
        try {
            $response = Send-SmokeRequest -Method ([System.Net.Http.HttpMethod]::Get) `
                -Path "/desktop/lifecycle/health" -Authenticated
            try {
                if ([int]$response.StatusCode -ge 200 -and
                    [int]$response.StatusCode -lt 300) {
                    $ready = $true
                    break
                }
                $lastProbe = "HTTP $([int]$response.StatusCode)"
            } finally {
                $response.Dispose()
            }
        } catch {
            $lastProbe = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) {
        throw "JAR 未在 ${StartupTimeoutSeconds}s 内通过认证健康检查；最后结果: $lastProbe"
    }

    $response = Send-SmokeRequest -Method ([System.Net.Http.HttpMethod]::Get) `
        -Path "/desktop/lifecycle/health"
    try {
        if ([int]$response.StatusCode -ne 401) {
            throw "未认证健康检查应返回 401，实际为 $([int]$response.StatusCode)"
        }
    } finally {
        $response.Dispose()
    }

    $response = Send-SmokeRequest -Method ([System.Net.Http.HttpMethod]::Get) `
        -Path "/desktop/status" -Authenticated
    try {
        if ([int]$response.StatusCode -ne 200) {
            throw "状态接口返回 $([int]$response.StatusCode)"
        }
        $statusJson = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $null = $statusJson | ConvertFrom-Json -ErrorAction Stop
    } finally {
        $response.Dispose()
    }

    $response = Send-SmokeRequest -Method ([System.Net.Http.HttpMethod]::Get) `
        -Path "/desktop-ui/index.html"
    try {
        if ([int]$response.StatusCode -ne 200) {
            throw "桌面静态资源返回 $([int]$response.StatusCode)"
        }
        $html = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $html.Contains("SelfAnalyst")) {
            throw "桌面入口不包含预期产品标识"
        }
    } finally {
        $response.Dispose()
    }

    $response = Send-SmokeRequest -Method ([System.Net.Http.HttpMethod]::Post) `
        -Path "/desktop/lifecycle/shutdown" -Authenticated
    try {
        if ([int]$response.StatusCode -ne 202) {
            throw "关闭接口返回 $([int]$response.StatusCode)"
        }
    } finally {
        $response.Dispose()
    }

    if (-not $process.WaitForExit(15000)) {
        throw "JAR 未在关闭请求后 15s 内退出"
    }
    if ($process.ExitCode -ne 0) {
        throw "JAR 关闭后的退出码为 $($process.ExitCode)"
    }

    Write-Host "打包 JAR 冒烟测试通过: $JarPath" -ForegroundColor Green
} catch {
    if ($process -and -not $process.HasExited) {
        $process.Kill($true)
        $process.WaitForExit()
    }
    $stdout = if ($stdoutTask) { $stdoutTask.GetAwaiter().GetResult() } else { "" }
    $stderr = if ($stderrTask) { $stderrTask.GetAwaiter().GetResult() } else { "" }
    if ($stdout) { Write-Host $stdout }
    if ($stderr) { Write-Error $stderr -ErrorAction Continue }
    throw
} finally {
    $client.Dispose()
    if ($process) {
        if (-not $process.HasExited) {
            $process.Kill($true)
            $process.WaitForExit()
        }
        $process.Dispose()
    }
    Remove-Item -LiteralPath $SmokeRoot -Recurse -Force -ErrorAction SilentlyContinue
}
