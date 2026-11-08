# Архитектура решения

## Контекст

LocalVoices 6.0 — Android-приложение для полностью локальной расшифровки разговоров, определения говорящих и экспорта структурированного результата.

## Компоненты

- Android UI на Kotlin/Compose — импорт MP3, диктофон, список диалогов, расшифровка и экспорт.
- `core` — доменные модели, таймкоды, экспорт и алгоритмы диаризации.
- `gigaam` — JNI/native inference GigaAM.
- `llm` — локальная Qwen2.5 через llama.cpp для пунктуации.
- Room + WorkManager — хранение результатов и очередь обработки.

## Основной поток

`MP3/микрофон → MediaCodec → PCM 16 кГц mono → Silero VAD → GigaAM ASR → segmentation → speaker embeddings → HDBSCAN → таймкоды → Qwen2.5 punctuation → Room → JSON/TXT/SRT`

## Зависимости и модели

GigaAM-v3 E2E CTC, Silero VAD, speaker segmentation, WeSpeaker encoder, HDBSCAN, Qwen2.5-0.5B-Instruct Q4_K_M, llama.cpp, Sherpa ONNX.

## Безопасность и приватность

Аудио и промежуточные данные не отправляются во внешние сервисы. Интернет не нужен для основной задачи. Модели встроены в проверенный APK.
