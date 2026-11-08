[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Audio,
    [ValidateSet('all', 'core', 'gigaam', 'whisper')]
    [string]$Backend = 'all',
    [string]$GigaAmModel = 'models/gigaam-v3-e2e-ctc-Q4_K_M.gguf',
    [string]$WhisperModel = 'models/ggml-base.bin',
    [string]$OutDir = 'build/local-tests'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = Join-Path $root $OutDir
New-Item -ItemType Directory -Force -Path $out | Out-Null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDir = Join-Path $out $runId
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Write-Result([string]$Name, [string]$Status, [string]$Message, [double]$Seconds = 0) {
    [pscustomobject]@{ name = $Name; status = $Status; message = $Message; seconds = $Seconds } | ConvertTo-Json -Compress | Add-Content -LiteralPath (Join-Path $runDir 'events.jsonl')
    Write-Host ("[{0}] {1}: {2}" -f $Status, $Name, $Message)
}

$jdk = Join-Path $root '.tools/jdk/jdk-21.0.13+9'
if (Test-Path $jdk) {
    $env:JAVA_HOME = $jdk
    $env:PATH = "$jdk\bin;$env:PATH"
}
$sdk = Join-Path $root '.tools/android-sdk'
if (Test-Path $sdk) {
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
}

$gradle = Join-Path $root 'gradlew.bat'
if ($Backend -in @('all', 'core')) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        & $gradle ':core:test' '--no-daemon' '--console=plain' *> (Join-Path $runDir 'core-test.log')
        if ($LASTEXITCODE -ne 0) { throw "Gradle core tests exited with $LASTEXITCODE" }
        $timer.Stop(); Write-Result 'core-tests' 'PASS' 'Kotlin core regression tests passed' $timer.Elapsed.TotalSeconds
    } catch {
        $timer.Stop(); Write-Result 'core-tests' 'FAIL' $_.Exception.Message $timer.Elapsed.TotalSeconds
        if ($Backend -eq 'core') { throw }
    }
}

if ($Audio) {
    $audioPath = (Resolve-Path $Audio).Path
    if (-not (Test-Path $audioPath)) { throw "Audio file not found: $Audio" }
    $audioInfo = Get-Item -LiteralPath $audioPath
    $audioInfo | Select-Object FullName, Length, Extension, LastWriteTime | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDir 'audio.json') -Encoding UTF8
    Write-Result 'audio-input' 'PASS' ("{0} ({1} bytes)" -f $audioInfo.FullName, $audioInfo.Length)
} else {
    Write-Result 'audio-input' 'SKIP' 'Pass an audio path to run native inference, for example -Audio .\\sample.wav'
}

function Find-NativeCli([string]$Kind) {
    $names = if ($Kind -eq 'gigaam') { @('transcribe-cli.exe', 'transcribe-cli') } else { @('whisper-cli.exe', 'whisper-cli') }
    $candidates = @(
        (Join-Path $root 'third_party/transcribe.cpp/build/bin/Release'),
        (Join-Path $root 'third_party/transcribe.cpp/build/bin'),
        (Join-Path $root 'third_party/whisper.cpp/build/bin/Release'),
        (Join-Path $root 'third_party/whisper.cpp/build/bin'),
        (Join-Path $root '.tools/native-smoke')
    )
    foreach ($dir in $candidates) {
        foreach ($name in $names) {
            $candidate = Join-Path $dir $name
            if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
        }
    }
    $command = Get-Command $names[0] -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

function Run-Native([string]$Kind, [string]$Model) {
    if (-not $Audio) { Write-Result "$Kind-native" 'SKIP' 'No -Audio argument'; return }
    $cli = Find-NativeCli $Kind
    if (-not $cli) {
        Write-Result "$Kind-native" 'SKIP' "Desktop $Kind CLI was not found. Build transcribe.cpp/whisper.cpp or pass its binary on PATH."
        return
    }
    $modelPath = (Resolve-Path (Join-Path $root $Model) -ErrorAction SilentlyContinue)
    if (-not $modelPath) {
        Write-Result "$Kind-native" 'SKIP' "Model not found: $Model"
        return
    }
    $log = Join-Path $runDir "$Kind-native.log"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        if ($Kind -eq 'gigaam') {
            & $cli '--backend' 'cpu' '--threads' ([Math]::Max(1, [Environment]::ProcessorCount - 1)) '--model' $modelPath.Path '--language' 'ru' $audioPath *> $log
        } else {
            & $cli '--model' $modelPath.Path '--language' 'ru' $audioPath *> $log
        }
        if ($LASTEXITCODE -ne 0) { throw "$Kind CLI exited with $LASTEXITCODE" }
        $timer.Stop(); Write-Result "$Kind-native" 'PASS' "Native output saved to $log" $timer.Elapsed.TotalSeconds
    } catch {
        $timer.Stop(); Write-Result "$Kind-native" 'FAIL' $_.Exception.Message $timer.Elapsed.TotalSeconds
    }
}

if ($Backend -in @('all', 'gigaam')) { Run-Native 'gigaam' $GigaAmModel }
if ($Backend -in @('all', 'whisper')) { Run-Native 'whisper' $WhisperModel }

$summary = [pscustomobject]@{
    run_id = $runId
    root = $root
    audio = if ($Audio) { (Resolve-Path $Audio).Path } else { $null }
    backend = $Backend
    notes = @(
        'This harness never uploads audio and never requires an APK.',
        'Native inference requires a desktop CLI; Android JNI behavior still needs APK/device or an equivalent native smoke test.',
        'Use events.jsonl and native logs for a reproducible timing record.'
    )
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDir 'summary.json') -Encoding UTF8
Write-Host "Report: $runDir"
