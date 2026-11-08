# Локальная проверка без телефона

В проект добавлен `scripts/test-local.ps1`. Он запускает Kotlin-регрессии и, если на ноутбуке есть desktop CLI, выполняет реальный локальный inference на WAV/PCM-файле. Аудио никуда не отправляется, APK для этого режима не нужен.

## Быстрая проверка алгоритмов

Из корня проекта:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-local.ps1 -Backend core
```

Скрипт использует встроенный JDK из `.tools/jdk` и сохраняет отчёт в `build/local-tests/<дата-время>/`.

## Проверка своего WAV

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-local.ps1 `
  -Audio 'C:\путь\к\разговору.wav' `
  -Backend all
```

В каталоге отчёта появятся:

- `core-test.log` — результаты автоматических Kotlin-тестов;
- `gigaam-native.log` — вывод desktop GigaAM CLI, если он найден;
- `whisper-native.log` — вывод desktop Whisper CLI, если он найден;
- `audio.json` — сведения об исходном файле;
- `events.jsonl` и `summary.json` — статусы и время стадий.

Поддерживается тестирование одного backend:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-local.ps1 -Audio .\sample.wav -Backend gigaam
powershell -ExecutionPolicy Bypass -File .\scripts\test-local.ps1 -Audio .\sample.wav -Backend whisper
```

## Что важно понимать

`core`-режим проверяет реконструкцию SentencePiece, сохранение повторов, CTC-границы и speaker merge. Это те же чистые алгоритмы, которые используются Android-кодом.

Desktop native inference проверяет модель и C++ runtime локально. Если CLI не найден, скрипт не маскирует это под успех, а пишет `SKIP` и объясняет, что требуется собрать `transcribe.cpp` или `whisper.cpp`.

Полная проверка Android-specific JNI, Room, WorkManager, фоновой записи и загрузки bundled assets всё равно требует APK/эмулятора/устройства. Её нельзя честно заменить desktop-тестом.

## Подготовка desktop CLI

Для GigaAM нужен `transcribe-cli` из `third_party/transcribe.cpp`, собранный с поддержкой GigaAM. Для Whisper нужен `whisper-cli` из `third_party/whisper.cpp`. Если бинарники не находятся автоматически, положите их в соответствующий `build/bin/Release` или добавьте в `PATH`.

Модель по умолчанию для GigaAM:

```text
models/gigaam-v3-e2e-ctc-Q4_K_M.gguf
```

Модель по умолчанию для Whisper:

```text
models/ggml-base.bin
```

Путь можно изменить параметрами `-GigaAmModel` и `-WhisperModel`.

## Сборка APK для контрольной проверки

```powershell
$env:JAVA_HOME=(Resolve-Path '.tools\jdk\jdk-21.0.13+9').Path
$env:ANDROID_HOME=(Resolve-Path '.tools\android-sdk').Path
.\gradlew.bat :app:assembleWhisperDebug :app:assembleGigaamDebug --no-daemon
```

APK находятся в:

```text
app/build/outputs/apk/whisper/debug/app-whisper-debug.apk
app/build/outputs/apk/gigaam/debug/app-gigaam-debug.apk
```

