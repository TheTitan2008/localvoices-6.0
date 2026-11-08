# Проверка APK

Дата: 7 ноября 2026.

| APK | Package ID | ASR | Модели внутри | Статус |
|---|---|---|---|---|
| `deliverables/LocalVoices-Optimized-Whisper-debug.apk` | `ru.localvoices` | Whisper base | Whisper, Silero, speaker, segmentation, punctuation | Собрана |
| `deliverables/LocalVoices-Optimized-GigaAM-debug.apk` | `ru.localvoices.gigaam` | GigaAM-v3 E2E CTC | Whisper asset, GigaAM, Silero, speaker, segmentation, punctuation | Собрана |
| `deliverables/LocalVoices-ExperimentalCascade-Whisper-debug.apk` | `ru.localvoices` | Whisper base + optional cascade | Те же модели; cascade выключен по умолчанию | Собрана отдельно |
| `deliverables/LocalVoices-ExperimentalCascade-GigaAM-debug.apk` | `ru.localvoices.gigaam` | GigaAM-v3 E2E CTC + optional cascade | Те же модели; cascade выключен по умолчанию | Собрана отдельно |
| `deliverables/LocalVoices-4.1-Whisper.apk` | `ru.localvoices.v41.whisper` | Whisper + live preview | Отдельное приложение для параллельного сравнения | Собрана |
| `deliverables/LocalVoices-4.1-GigaAM.apk` | `ru.localvoices.v41.gigaam` | GigaAM + live preview | Основной backend; GGUF встроен | Собрана |
| `deliverables/LocalVoices-4.0-Optimized-Whisper.apk` | `ru.localvoices.v40.optimized.whisper` | Optimized Whisper | Отдельное приложение | Собрана |
| `deliverables/LocalVoices-4.0-Optimized-GigaAM.apk` | `ru.localvoices.v40.optimized.gigaam` | Optimized GigaAM | Отдельное приложение; GGUF встроен | Собрана |
| `deliverables/LocalVoices-4.1-Cascade-Whisper.apk` | `ru.localvoices.v41.cascade.whisper` | Whisper + Cascade | Cascade включён по умолчанию | Собрана |
| `deliverables/LocalVoices-4.1-Cascade-GigaAM.apk` | `ru.localvoices.v41.cascade.gigaam` | GigaAM + Cascade | Cascade включён по умолчанию; GGUF встроен | Собрана |

Проверено через APK manifest: разные package IDs позволяют устанавливать варианты одновременно. Проверено через содержимое APK: необходимые bundled model assets присутствуют.

Не проверено без устройства: разрешения микрофона, импорт MP3/M4A, Room, WorkManager, фактический JNI inference, фоновые ограничения Android и thermal throttling.

