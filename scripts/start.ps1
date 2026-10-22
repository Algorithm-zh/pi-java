<#
.SYNOPSIS
    启动 pi-java 本地服务（demo profile：H2 文件库 + 可选的真实模型档案）。

.DESCRIPTION
    这个脚本解决三件启动时最容易踩的坑：

    1. JDK 版本——PATH 上的 java 可能不是 21，直接用会报
       UnsupportedClassVersionError。脚本按 JAVA_HOME → .jdks 目录 → PATH 的顺序
       找出真正可用的 JDK 21+，并显式使用它的 java.exe。
    2. 密钥——Key 只从环境变量读取，不落盘、不打印。未设置时只告警不退出，
       这样仍可用 Fake 模型做离线演示。
    3. 模型档案——首次运行时自动创建 DeepSeek 档案（只存环境变量名），
       之后每次启动都检查一次，已存在则跳过。

.PARAMETER Port
    监听端口，默认 8080。

.PARAMETER SkipModel
    跳过模型档案的创建/检查，只启动服务。

.PARAMETER Profile
    模型档案 ID，默认 deepseek。

.EXAMPLE
    $env:PI_DEEPSEEK_KEY = "sk-xxxx"
    .\scripts\start.ps1

.EXAMPLE
    .\scripts\start.ps1 -Port 8081 -SkipModel
#>
[CmdletBinding()]
param(
    [int]$Port = 8080,
    [switch]$SkipModel,
    [string]$Profile = "deepseek"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# ── 路径与可调参数 ────────────────────────────────────────────────────────────
$RepoRoot = Split-Path -Parent $PSScriptRoot
$JarPath = Join-Path $RepoRoot "pi-java-app\target\pi-java-app-0.1.0-SNAPSHOT.jar"
$BaseUrl = "http://127.0.0.1:$Port/api/v1"

# DeepSeek 走 OpenAI-compatible 协议。换其它服务时只改下面四行即可。
$ApiKeyEnv = "PI_DEEPSEEK_KEY"
$ModelBaseUrl = "https://api.deepseek.com/v1"
$ModelName = "deepseek-chat"
$ContextWindow = 64000
$MaxOutputTokens = 8192

$MinimumJavaVersion = 21

# ── 工具函数 ──────────────────────────────────────────────────────────────────
function Write-Step([string]$Message) {
    Write-Host "[pi-java] $Message" -ForegroundColor Cyan
}

function Write-Warn([string]$Message) {
    Write-Host "[pi-java] $Message" -ForegroundColor Yellow
}

function Write-Fail([string]$Message) {
    Write-Host "[pi-java] $Message" -ForegroundColor Red
}

<#
  解析某个 java.exe 的主版本号；不可执行或解析失败返回 0。
#>
function Get-JavaMajorVersion([string]$JavaExe) {
    if (-not (Test-Path -LiteralPath $JavaExe)) {
        return 0
    }
    try {
        # java -version 输出到 stderr，这里合并后取首个匹配
        $output = & $JavaExe -version 2>&1 | Out-String
    } catch {
        return 0
    }
    if ($output -match 'version "(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        # 1.8.0 这类老式版本号取第二段
        if ($major -eq 1 -and $Matches[2]) {
            return [int]$Matches[2]
        }
        return $major
    }
    return 0
}

<#
  找出可用的 JDK 21+。优先级：
  1. 显式传入的 JAVA_HOME（尊重用户已配置的环境）
  2. 常见的 IDE JDK 目录（~/.jdks）
  3. PATH 上的 java

  找不到时返回 $null，由调用方给出可操作的提示。
#>
function Resolve-Java21 {
    $candidates = [System.Collections.Generic.List[string]]::new()

    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME "bin\java.exe"))
    }

    $jdkRoots = @(
        (Join-Path $env:USERPROFILE ".jdks"),
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium"
    )
    foreach ($root in $jdkRoots) {
        if (-not (Test-Path -LiteralPath $root)) {
            continue
        }
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add((Join-Path $_.FullName "bin\java.exe")) }
    }

    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) {
        $candidates.Add($onPath.Source)
    }

    foreach ($candidate in $candidates) {
        $major = Get-JavaMajorVersion $candidate
        if ($major -ge $MinimumJavaVersion) {
            return @{ Path = $candidate; Version = $major }
        }
    }
    return $null
}

<#
  等待服务就绪：轮询 /system/info，避免"刚启动就请求失败"。
#>
function Wait-ServiceReady([int]$TimeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $info = Invoke-RestMethod -Uri "$BaseUrl/system/info" -TimeoutSec 2
            if ($info) {
                return $info
            }
        } catch {
            # 服务尚未监听，继续等待
        }
        Start-Sleep -Milliseconds 800
    }
    return $null
}

<#
  确认模型档案存在；不存在则创建。已存在时不做任何修改，避免覆盖用户手工调整。
#>
function Ensure-ModelProfile {
    if (-not $env:PI_DEEPSEEK_KEY -and $ApiKeyEnv -eq "PI_DEEPSEEK_KEY") {
        Write-Warn "未检测到环境变量 $ApiKeyEnv；将跳过模型档案，服务只能用 Fake 模型做离线演示。"
        # 用单引号构造示例，避免 $env: 被插值、反引号被当成转义
        $example = '$env:' + $ApiKeyEnv + ' = "sk-你的key"'
        Write-Warn ('设置方式（仅当前终端有效）：' + $example)
        return
    }

    try {
        $models = Invoke-RestMethod -Uri "$BaseUrl/models" -TimeoutSec 5
        $exists = @($models) | Where-Object { $_.id -eq $Profile }
        if ($exists) {
            Write-Step "模型档案已存在：$Profile（$($exists[0].endpoint.modelName)）"
            return
        }
    } catch {
        Write-Warn "查询模型档案失败：$($_.Exception.Message)"
        return
    }

    $payload = @{
        id          = $Profile
        displayName = "DeepSeek Chat"
        endpoint    = @{
            provider        = "OPENAI_COMPATIBLE"
            baseUrl         = $ModelBaseUrl
            apiKeyEnv       = $ApiKeyEnv
            modelName       = $ModelName
            contextWindow   = $ContextWindow
            maxOutputTokens = $MaxOutputTokens
            capabilities    = @{ streaming = $true; toolCalls = $true; thinking = $false }
        }
    } | ConvertTo-Json -Depth 6

    try {
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/models" `
            -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 8 | Out-Null
        Write-Step "已创建模型档案：$Profile（$ModelName）"
    } catch {
        $detail = $_.ErrorDetails.Message
        Write-Warn "创建模型档案失败：$detail"
    }
}

# ── 前置检查 ──────────────────────────────────────────────────────────────────
Write-Step "仓库目录：$RepoRoot"

if (-not (Test-Path -LiteralPath $JarPath)) {
    Write-Fail "未找到应用 jar：$JarPath"
    Write-Fail "请先构建：mvn -q -DskipTests package"
    exit 1
}

$java = Resolve-Java21
if (-not $java) {
    Write-Fail "未找到 JDK $MinimumJavaVersion 或更高版本。"
    Write-Fail "当前 PATH 上的 java 版本可能是 $(Get-JavaMajorVersion (Get-Command java -ErrorAction SilentlyContinue).Source)。"
    Write-Fail "请安装 JDK $MinimumJavaVersion+，或把 JAVA_HOME 指向它。"
    exit 1
}
Write-Step "使用 JDK：$($java.Path)（版本 $($java.Version)）"

# 端口占用检查：先探测是否已有 pi-java 在跑
$inUse = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($inUse) {
    Write-Warn "端口 $Port 已被占用（PID $($inUse[0].OwningProcess)）。"
    Write-Warn "若不是本应用，请用 -Port 换一个端口。"
    exit 1
}

# ── 启动 ──────────────────────────────────────────────────────────────────────
$env:PI_SERVER_ADDRESS = "127.0.0.1"
Write-Step "启动服务：http://127.0.0.1:$Port"
Write-Step "密钥变量：$ApiKeyEnv（只从环境变量读取，不写入数据库）"

# 用后台进程启动，脚本自身负责等待就绪并注册模型档案，最后再交回前台等待退出
$process = Start-Process -FilePath $java.Path `
    -ArgumentList @("-jar", "`"$JarPath`"", "--server.port=$Port") `
    -WorkingDirectory $RepoRoot `
    -NoNewWindow -PassThru

try {
    $info = Wait-ServiceReady
    if (-not $info) {
        Write-Fail "服务在 90 秒内未就绪，请查看上方的启动日志。"
        exit 1
    }
    Write-Step "服务已就绪：版本 $($info.version)，工作目录 $($info.workspaceRoot)"

    if (-not $SkipModel) {
        Ensure-ModelProfile
    }

    Write-Host ""
    Write-Step "打开浏览器：http://127.0.0.1:$Port"
    Write-Step "新建会话时选择模型档案：$Profile（没有 Key 时选 Fake Demo）"
    Write-Step "按 Ctrl+C 停止服务"
    Write-Host ""

    Wait-Process -Id $process.Id
} finally {
    if ($process -and -not $process.HasExited) {
        Write-Step "正在停止服务（PID $($process.Id)）…"
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
}
