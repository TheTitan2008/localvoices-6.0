# Отчёт о производительности

## Измеренные операции

| Операция | Результат |
|---|---|
| Kotlin core tests | 25/25 PASS |
| Whisper debug APK | BUILD SUCCESSFUL, около 49 секунд incremental build |
| GigaAM debug APK | BUILD SUCCESSFUL, в составе того же Gradle запуска |
| Local harness core run | PASS; отчёт `build/local-tests/20261107-013923/` |

Размеры последних APK:

- `deliverables/LocalVoices-Optimized-Whisper-debug.apk` — 719 343 830 bytes;
- `deliverables/LocalVoices-Optimized-GigaAM-debug.apk` — 900 584 920 bytes.

Это debug APK с bundled models, поэтому размер нельзя сравнивать с release/App Bundle размером.

## Почему нет WER/RTF/RAM

В окружении нет desktop `transcribe-cli`/`whisper-cli` и не подключено Android-устройство с profiling. Нельзя честно выдавать время модели, peak RAM, WER или DER по одному факту компиляции.

Для локального замера:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-local.ps1 -Audio .\sample.wav -Backend all
```

Скрипт сохраняет stdout, wall time и статусы в `build/local-tests/<run>/`. Для Android нужны Perfetto/Android Studio profiler и одинаковые файлы на baseline/optimized APK.

