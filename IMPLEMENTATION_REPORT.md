# Отчёт о текущей реализации модернизации

Дата: 7 ноября 2026.

## Реализовано

- GigaAM теперь участвует в live-preview во время записи: первый черновой блок начинается после примерно 4 секунд речи, дальнейшие блоки ограничены 8 секундами; Whisper сохраняет отдельный режим 6–12 секунд.
- Добавлен `SpeechWindowPlanner`: speaker embeddings планируются только внутри пересечения Silero VAD и acoustic segmentation, поэтому длинные паузы не запускают тяжёлый encoder.
- `TranscriptMerger` дополнительно склеивает соседние блоки одного подтверждённого говорящего через паузу до 1,25 секунды; разные неизвестные кластеры по одному отображаемому имени не склеиваются.
- Добавлены отдельные comparison flavors 4.0/4.1 с уникальными application ID и именами для параллельной установки; матрица находится в `VERSION_MATRIX_4_1.md`.

- Добавлена модель `AsrUnit` с необязательными token ID, probability, segment/word indices и списком исходных token IDs. Старые вызовы с двумя аргументами остаются совместимыми.
- Добавлен `SentencePieceReconstructor.gigaAm()`. Он собирает pieces в целые слова, сохраняет provenance и не удаляет самостоятельный `▁` из raw-кэша.
- GigaAM JNI bridge теперь передаёт `id`, времена, segment index, word index, probability и raw piece. Сохранён fallback для старого четырёхполного формата.
- `TokenEntry.p == 0.0` не выдаётся как настоящая confidence: нулевой sentinel превращается в `null`.
- `RawCodec` сохраняет новые поля и умеет читать старый JSON-массив из трёх элементов.
- ASR-кэш читается до вызова native ASR. При попадании decode и сохранение не выполняются.
- Версия cache fingerprint поднята до `v5` и включает preprocessing/reconstruction versions.
- GigaAM words строятся до `TranscriptMerger`; speaker attribution работает по целым словам.
- `VoicesViewModel.remerge()` распознаёт старый GigaAM raw-кэш по `▁` и повторяет word reconstruction перед merge.
- Все качественные speaker windows участвуют в первичном anonymous clustering, включая окна с сильным известным совпадением. Для малой группы unresolved окон оставлен bounded homogeneous fallback, чтобы один known outlier не превращал остальные окна в noise.
- Добавлены core-регрессии для «пожалуйста», отдельного `▁`, повторов и token provenance.
- Добавлен `scripts/test-local.ps1` и [LOCAL_TESTING.md](LOCAL_TESTING.md) для проверки без APK на телефоне.
- Собраны обе debug APK после последних изменений.

## Проверено

- `:core:test`: 25 тестов успешно.
- `:app:assembleWhisperDebug`: успешно.
- `:app:assembleGigaamDebug`: успешно.
- Whisper package ID: `ru.localvoices`.
- GigaAM package ID: `ru.localvoices.gigaam`.
- Whisper APK содержит bundled Whisper, Silero, speaker, segmentation и punctuation models.
- GigaAM APK содержит те же модели и `gigaam-v3-e2e-ctc-Q4_K_M.gguf`.
- Local harness без аудио успешно запускает core tests и создаёт JSONL/JSON отчёт.

## Что ещё не доказано

- Реальный пользовательский MP3/звонок не был прогнан в полном desktop native inference в этом окружении: desktop `transcribe-cli` не найден.
- Не измерены WER/CER/DER/JER и real-time factor на эталонном русском корпусе.
- Не реализован настоящий learned Early Exit внутри GigaAM/Whisper. VAD gating не выдаётся за Early Exit.
- Стерео сохраняется не во всех старых путях декодирования, а ресемплинг ещё требует FIR/polyphase замены.
- Двойной segmentation/embedding путь требует отдельного профилирования на телефоне.
- Android-specific запись, WorkManager, Room migration и cancellation требуют устройства или эмулятора.

