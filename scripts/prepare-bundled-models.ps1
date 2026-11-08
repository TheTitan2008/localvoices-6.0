param([switch]$Copy)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$source = Join-Path $root 'models'
$target = Join-Path $root 'app/src/main/assets/models'
$hashes = [ordered]@{
    'ggml-base.bin' = '60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe'
    'silero_vad.onnx' = '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'
    'speaker.onnx' = '5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94'
    'segmentation.onnx' = 'd582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d'
    'punctuation.gguf' = '74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db'
}
New-Item -ItemType Directory -Force -Path $target | Out-Null
foreach ($name in $hashes.Keys) {
    $src = Join-Path $source $name
    $dst = Join-Path $target $name
    if (-not (Test-Path -LiteralPath $src)) { throw "Missing model: $src" }
    $actual = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $hashes[$name]) { throw "Wrong SHA-256 for ${name}: $actual" }
    if (Test-Path -LiteralPath $dst) { Remove-Item -LiteralPath $dst -Force }
    if ($Copy) { Copy-Item -LiteralPath $src -Destination $dst }
    else {
        try { New-Item -ItemType HardLink -Path $dst -Target $src | Out-Null }
        catch { Copy-Item -LiteralPath $src -Destination $dst }
    }
    Write-Host "$name OK"
}
