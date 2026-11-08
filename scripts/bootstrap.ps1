$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    New-Item -ItemType Directory -Force app/libs,third_party | Out-Null
    if (!(Test-Path third_party/llama.cpp/include/llama.h)) {
        git clone --depth 1 --branch b6800 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp
        if($LASTEXITCODE){throw 'llama.cpp clone failed'}
    }
    if ((git -C third_party/llama.cpp rev-parse HEAD) -ne '0398752dd450dfabdd1b9e289f6364c2600f6ab5') {throw 'Unexpected llama.cpp revision'}
    if (!(Test-Path third_party/whisper.cpp/src/whisper.cpp)) {
        git clone --depth 1 --branch v1.8.2 https://github.com/ggml-org/whisper.cpp.git third_party/whisper.cpp
        if ($LASTEXITCODE) { throw 'whisper.cpp clone failed' }
    }
    $revision = git -C third_party/whisper.cpp rev-parse HEAD
    if ($revision -ne '4979e04f5dcaccb36057e059bbaed8a2f5288315') { throw 'Unexpected whisper.cpp revision' }
    $source = Join-Path $root 'third_party/whisper.cpp/src/whisper.cpp'
    $code = [IO.File]::ReadAllText($source)
    if (!$code.Contains('localvoices_encoder_pool')) {
        [IO.File]::AppendAllText($source, "`n" + [IO.File]::ReadAllText((Join-Path $root 'native/encoder_pool.inc')))
    }
    if (!(Test-Path app/libs/sherpa-onnx-1.12.15.aar)) {
        Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.15/sherpa-onnx-1.12.15.aar' -OutFile app/libs/sherpa-onnx-1.12.15.aar
    }
    $hash = (Get-FileHash app/libs/sherpa-onnx-1.12.15.aar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne '6994c53380846107b0363fee02fa9db6176c61c746a2315893c6b01e7f6d4c1d') { throw 'Sherpa AAR SHA-256 mismatch' }
    Write-Output 'Pinned native dependencies ready.'
} finally { Pop-Location }
