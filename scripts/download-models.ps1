param(
    [string]$Destination = (Join-Path (Split-Path $PSScriptRoot -Parent) 'models'),
    [ValidateSet('tiny','base','small')][string]$Whisper = 'base'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Destination | Out-Null
$segmentation=Join-Path $Destination 'segmentation.onnx'
if (!(Test-Path $segmentation)) {
    $archive=Join-Path $Destination 'segmentation.tar.bz2'
    Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2' -OutFile $archive
    if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne '24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488') { throw 'Segmentation archive SHA-256 mismatch' }
    & tar -xf $archive -C $Destination 'sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx' 'sherpa-onnx-pyannote-segmentation-3-0/LICENSE'
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract segmentation model' }
    Copy-Item -LiteralPath (Join-Path $Destination 'sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx') -Destination $segmentation
    Copy-Item -LiteralPath (Join-Path $Destination 'sherpa-onnx-pyannote-segmentation-3-0/LICENSE') -Destination (Join-Path $Destination 'segmentation-LICENSE.txt')
}
$whisperHashes = @{
    tiny='be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21'
    base='60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe'
    small='1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b'
}
$models = @(
    @{ Name='segmentation.onnx'; Url='https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models'; Hash='d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d' },
    @{ Name='punctuation.gguf'; Url='https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf'; Hash='74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db' },
    @{ Name="ggml-$Whisper.bin"; Url="https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-$Whisper.bin"; Hash=$whisperHashes[$Whisper] },
    @{ Name='silero_vad.onnx'; Url='https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx'; Hash='9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6' },
    @{ Name='speaker.onnx'; Url='https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx'; Hash='5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94' }
)
$manifest = @()
foreach ($model in $models) {
    $target = Join-Path $Destination $model.Name
    if (!(Test-Path $target)) {
        Write-Output "Downloading $($model.Name)"
        Invoke-WebRequest $model.Url -OutFile "$target.part"
        Move-Item -LiteralPath "$target.part" -Destination $target -Force
    }
    $hash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $model.Hash) { throw "Model SHA-256 mismatch: $($model.Name)" }
    $manifest += @{ name=$model.Name; url=$model.Url; sha256=$hash }
}
$manifest | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $Destination 'manifest.json')
Write-Output "Models are in $Destination. Import them through Android Settings."
Add-Type -AssemblyName System.IO.Compression
$pack=Join-Path $Destination 'local-voices-models.zip'
$stream=[IO.File]::Open($pack,[IO.FileMode]::Create)
$zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
try { foreach($model in $models) { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $Destination $model.Name),$model.Name,[IO.Compression.CompressionLevel]::NoCompression) | Out-Null } } finally {$zip.Dispose();$stream.Dispose()}
Write-Output "Offline model pack: $pack"
