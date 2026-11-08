# Сравнительные APK 4.0/4.1

Дата: 7 ноября 2026.

## LocalVoices 5.0 GigaAM

`deliverables/LocalVoices-5.0-GigaAM.apk` (`ru.localvoices.v50.gigaam`) is the
new primary GigaAM build. It uses short GigaAM ASR blocks (up to 24 seconds),
timestamp validation, word-midpoint speaker assignment, temporal smoothing,
message coalescing, pre-embedding silence/quality gating, and provisional
speaker embeddings during recording. Final global clustering still runs after
recording stops.

Новые comparison-сборки имеют разные `applicationId` и отображаемые имена, поэтому их можно устанавливать одновременно:

| APK | Application ID | Назначение |
|---|---|---|
| `deliverables/LocalVoices-4.1-GigaAM.apk` | `ru.localvoices.v41.gigaam` | Основной вариант: GigaAM-v3, live-preview GigaAM во время записи |
| `deliverables/LocalVoices-4.1-Whisper.apk` | `ru.localvoices.v41.whisper` | Сравнительный Whisper-вариант с live-preview Whisper |
| `deliverables/LocalVoices-4.0-Optimized-GigaAM.apk` | `ru.localvoices.v40.optimized.gigaam` | Optimized GigaAM: cluster-first, cache, VAD-gated embeddings |
| `deliverables/LocalVoices-4.0-Optimized-Whisper.apk` | `ru.localvoices.v40.optimized.whisper` | Optimized Whisper: тот же постпроцессинг и VAD-gated embeddings |
| `deliverables/LocalVoices-4.1-Cascade-GigaAM.apk` | `ru.localvoices.v41.cascade.gigaam` | GigaAM + экспериментальный каскад, включён по умолчанию |
| `deliverables/LocalVoices-4.1-Cascade-Whisper.apk` | `ru.localvoices.v41.cascade.whisper` | Whisper + экспериментальный каскад, включён по умолчанию |

## Что общего в 4.1

- Во время записи черновой ASR работает параллельно с микрофоном. Для GigaAM первый preview-блок готовится примерно после 4 секунд речи, далее проверяются блоки до 8 секунд; Whisper сохраняет режим 6–12 секунд.
- В финальном pipeline speaker-encoder запускается только на пересечении акустической segmentation с Silero VAD. Длинные паузы не становятся embedding-окнами.
- Соседние реплики одного подтверждённого говорящего объединяются через паузу до 1,25 секунды. Разные неизвестные кластеры не объединяются по одному имени.

## Важное ограничение исторических APK

Файлы `LocalVoices-2.0-debug.apk`, `LocalVoices-3.0-all-in-one.apk`, `LocalVoices-3.1-one-sample.apk`, старые `4.0-Whisper/GigaAM` сохранены как точные архивные сборки. Их исходные package ID повторяются, поэтому для параллельного сравнения старых снимков используйте отдельные эмуляторы или удаляйте предыдущий пакет. Новые 4.0/4.1 comparison APK устанавливаются рядом друг с другом.
