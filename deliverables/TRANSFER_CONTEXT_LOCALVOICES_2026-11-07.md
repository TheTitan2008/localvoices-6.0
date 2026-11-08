# LocalVoices — полный контекст для переноса в другой чат

Дата: 7 ноября 2026.

Цель файла — передать другому разработчику полное состояние проекта, различия APK, реализованные алгоритмы, найденные проблемы, ограничения и команды проверки.

## 1. Главная текущая рекомендация

Основным backend выбран GigaAM-v3: по пользовательской проверке текст получается лучше Whisper. Для основного сравнения использовать:

`deliverables/LocalVoices-4.1-GigaAM.apk`

Package ID: `ru.localvoices.v41.gigaam`.

Это отдельное приложение, его можно устанавливать одновременно с остальными новыми comparison APK.

## 2. Список APK

### Архивные версии

#### LocalVoices-2.0-debug.apk

Ранняя базовая версия. Есть импорт аудио, Whisper, VAD, speaker profiles, диаризация и экспорт. Это baseline для исторического сравнения.

Package ID: `ru.localvoices`, versionCode 2.

#### LocalVoices-3.0-all-in-one.apk

Версия со встроенными моделями:

- Whisper base;
- Silero VAD;
- WeSpeaker;
- segmentation;
- Qwen punctuation;
- импорт MP3/WAV/M4A;
- запись микрофона;
- JSON/TXT/SRT;
- связи людей между диалогами;
- полностью локальная работа.

Package ID: `ru.localvoices`, versionCode 3.

#### LocalVoices-3.1-one-sample.apk

Исправление регистрации голоса:

- один длинный голосовой образец;
- образец не обрезается до 15 секунд;
- анализируется вся запись;
- добавлены источники микрофона `VOICE_RECOGNITION`, `MIC`, `UNPROCESSED`;
- исправлена часть ошибок диаризации.

Package ID: `ru.localvoices`, versionCode 4.

#### LocalVoices-4.0-Whisper.apk и LocalVoices-4.0-GigaAM.apk

Исторические отдельные backend-версии. Сохраняются как архивные сборки. Их package ID повторяются с основными старыми приложениями, поэтому одновременно с ними установить их нельзя без отдельного эмулятора.

### Новые comparison APK с уникальными package ID

#### LocalVoices-4.1-GigaAM.apk

`ru.localvoices.v41.gigaam`

- основной GigaAM-v3 backend;
- встроенный `gigaam-v3-e2e-ctc-Q4_K_M.gguf`;
- встроенный live-preview во время записи;
- первый preview-блок примерно после 4 секунд речи;
- следующие preview-блоки до 8 секунд;
- SentencePiece реконструкция в целые слова;
- встроенная пунктуация GigaAM;
- VAD-gated speaker embeddings;
- склейка соседних реплик одного говорящего.

#### LocalVoices-4.1-Whisper.apk

`ru.localvoices.v41.whisper`

- Whisper backend;
- live-preview Whisper во время записи;
- preview блоки 6–12 секунд;
- локальная пунктуация Qwen с проверкой сохранения слов;
- VAD-gated speaker embeddings;
- склейка соседних реплик одного говорящего.

#### LocalVoices-4.0-Optimized-GigaAM.apk

`ru.localvoices.v40.optimized.gigaam`

- GigaAM;
- cluster-first diarization;
- ASR и embedding cache;
- VAD-gated embeddings;
- отдельное приложение для сравнения.

#### LocalVoices-4.0-Optimized-Whisper.apk

`ru.localvoices.v40.optimized.whisper`

- Whisper;
- cluster-first diarization;
- cache;
- VAD-gated embeddings;
- отдельное приложение для сравнения.

#### LocalVoices-4.1-Cascade-GigaAM.apk

`ru.localvoices.v41.cascade.gigaam`

- GigaAM;
- экспериментальный каскад включён по умолчанию;
- дешёвый фильтр почти полной тишины перед ASR;
- quality gate перед speaker encoder;
- редкие embeddings на длинных чистых монологах;
- плотный режим сохраняется для коротких, шумных и overlapping участков.

#### LocalVoices-4.1-Cascade-Whisper.apk

`ru.localvoices.v41.cascade.whisper`

То же каскадное ускорение для Whisper.

Полная таблица: `VERSION_MATRIX_4_1.md`.

## 3. Что реализовано в основном приложении

### Ввод и вывод

- запись через микрофон;
- foreground service;
- продолжение записи при сворачивании приложения;
- импорт MP3/WAV/M4A через Android MediaExtractor/MediaCodec;
- преобразование в PCM16, 16 кГц, mono;
- отдельный UUID для каждого диалога;
- экспорт одного диалога в JSON/TXT/SRT;
- экспорт всех диалогов в ZIP с `project.json`;
- JSON содержит `speaker_id`, `start_ms`, `end_ms`, `text`.

### ASR

Whisper:

- whisper.cpp native CPU inference;
- word/token timestamps;
- русский язык основной;
- английский доступен как вторичный;
- Qwen punctuation запускается после ASR;
- если Qwen изменяет слова или числа, результат отклоняется и остаётся исходный текст.

GigaAM:

- native GigaAM bridge;
- GGUF Q4_K_M встроен в APK;
- native token ID, timestamps, segment/word index и probability;
- сохранение raw SentencePiece pieces;
- reconstruction до целых слов перед speaker attribution;
- GigaAM punctuation остаётся встроенной в результат.

### Live-preview во время записи

Файл: `app/src/main/java/ru/localvoices/audio/CaptureService.kt`.

Микрофон пишет WAV, параллельно запускается preview coroutine. Preview использует backend текущего APK:

- GigaAM: блоки примерно 4–8 секунд;
- Whisper: блоки примерно 6–12 секунд;
- VAD сначала проверяет наличие речи;
- черновик отображается до остановки записи;
- после остановки preview закрывается, затем запускается полный ProcessWorker.

Preview является предварительным текстом и не заменяет финальную обработку: финальный pipeline повторно делает VAD, ASR, segmentation, speaker embeddings, clustering и экспорт.

### Диаризация

Используются:

- Silero VAD;
- Sherpa/Pyannote segmentation INT8;
- WeSpeaker ResNet34 256-мерные embeddings;
- HDBSCAN;
- профили известных людей в Room;
- связи людей между диалогами.

Изменён порядок:

1. качественные окна всего диалога участвуют в кластеризации;
2. известные профили сопоставляются после cluster-first шага;
3. похожий чужой голос не исключается из неизвестной кластеризации заранее;
4. одиночные неизвестные фрагменты не принудительно превращаются в человека.

### Склейка реплик

Файл: `core/src/main/kotlin/ru/localvoices/core/Diarizer.kt`.

`TranscriptMerger` теперь объединяет соседние блоки, если:

- speaker ID совпадает;
- либо совпадает подтверждённое имя;
- speaker не является `null`;
- имя не является `Неизвестный` или `Говорящий N`;
- пауза не больше 1,25 секунды.

Это исправляет ситуацию, когда один человек подряд отображался тремя отдельными блоками.

Разные неизвестные кластеры по имени не склеиваются.

### VAD-gated speaker windows

Файл: `core/src/main/kotlin/ru/localvoices/core/Domain.kt`, объект `SpeechWindowPlanner`.

До запуска тяжёлого speaker encoder:

1. берутся acoustic segmentation spans;
2. они пересекаются с Silero VAD spans;
3. участки длинной тишины отбрасываются;
4. окна строятся только внутри речевых островков;
5. для обычного режима используется до 2,5 секунд с шагом 1,25 секунды;
6. Cascade на длинных чистых участках использует примерно 3 секунды с шагом 3,75 секунды;
7. шумные, короткие и overlapping участки не прореживаются так агрессивно.

Это уменьшает количество дорогих embeddings и не влияет на сохранение ASR-текста: VAD gating относится к speaker encoder, а не к финальному тексту.

### Cache и память

- ASR cache читается до вызова native ASR;
- embedding cache сохраняется атомарно;
- fingerprint включает backend, язык, модель, encoder, preprocessing, reconstruction и cascade flag;
- Whisper освобождается до тяжёлой диаризации;
- LLM загружается после acoustic stages;
- HDBSCAN использует треугольную матрицу и ограничение количества окон;
- количество CPU-потоков ограничивается ядрами устройства.

## 4. Найденные основные проблемы

### Проблемы качества

- похожий чужой голос может быть ошибочно принят за известного человека;
- короткие голосовые фрагменты иногда остаются `Неизвестный`;
- overlap определяется, но два источника речи пока не разделяются;
- слабый или зашумлённый звук ухудшает embeddings;
- текущие миллисекундные timestamps — единицы формата, а не доказанная миллисекундная точность;
- GigaAM и Whisper могут по-разному разбивать границы фраз;
- Qwen punctuation не всегда улучшает текст, поэтому есть строгая проверка и fallback.

### Проблемы скорости

- полный финальный pipeline всё ещё может быть медленнее реального времени на слабом эмуляторе;
- GigaAM APK большой из-за встроенной GGUF-модели;
- Android Emulator не показывает реальную скорость ARM-телефона;
- несколько GigaAM APK нельзя собирать параллельно с маленьким heap: при сборке нужен Gradle heap около 6 ГБ и последовательная упаковка.

### Что пока не доказано

- нет корпусных WER/CER/DER/JER;
- нет измеренного RTF на официальном стенде организаторов;
- нет peak RAM и thermal profiling на ARM64-телефоне;
- desktop native `whisper-cli`/`transcribe-cli` в окружении не найден;
- полная проверка Android 8–15 на физических устройствах не проведена;
- официальная длина тестового подкаста организаторами не сообщалась.

## 5. Что исследовалось и какие идеи использованы

- официальный GigaAM возвращает текст и word timing, поэтому нативные границы слов сохраняются;
- representative/core sampling используется для уменьшения числа embeddings на стабильных участках;
- ONNX Runtime threading и graph optimization важны для последующего ARM profiling;
- настоящий learned Early Exit внутри GigaAM/Whisper пока не реализован;
- текущий каскад — инженерный VAD/quality gate, а не обученный early-exit classifier.

## 6. Проверки

Последний core-прогон:

```powershell
$env:JAVA_HOME=(Resolve-Path '.tools\\jdk\\jdk-21.0.13+9').Path
.\\gradlew.bat :core:test --no-daemon --console=plain
```

Результат: 29 тестов успешно.

Собраны и проверены:

- GigaAM 4.1;
- GigaAM 4.0 Optimized;
- GigaAM 4.1 Cascade;
- Whisper 4.1;
- Whisper 4.0 Optimized;
- Whisper 4.1 Cascade.

В GigaAM comparison APK проверено наличие:

```text
assets/models/gigaam-v3-e2e-ctc-Q4_K_M.gguf
```

## 7. Как тестировать на ноутбуке

Использовать Android Studio Emulator:

- Android 15 / API 35;
- Pixel 7;
- x86_64 image для обычного Windows-ноутбука;
- RAM 4096 MB;
- storage 8 GB или больше.

Установка новых APK не требует удаления других приложений, потому что package ID уникальны:

```powershell
adb install "D:\\HSE_study_match\\MIFI_hack_67_11\\deliverables\\LocalVoices-4.1-GigaAM.apk"
adb install "D:\\HSE_study_match\\MIFI_hack_67_11\\deliverables\\LocalVoices-4.1-Cascade-GigaAM.apk"
```

Передать аудио:

```powershell
adb push ".\\sample.mp3" "/sdcard/Download/sample.mp3"
```

Для честного сравнения использовать один и тот же файл и одинаковый эмулятор. Проверять:

1. первый запуск и подготовку моделей;
2. импорт MP3/WAV;
3. live-preview во время записи;
4. финальный текст после остановки;
5. склейку соседних блоков одного говорящего;
6. неизвестные кластеры;
7. экспорт JSON;
8. повторный запуск с cache;
9. работу при выключенном интернете;
10. время обработки и RAM.

RTF считается как `processing_seconds / audio_duration_seconds`; меньше — лучше.

## 8. Важное для следующего разработчика

- Не удалять старые APK и не менять их package ID без отдельной задачи.
- Основным для качества текста считать GigaAM 4.1.
- Не смешивать кэш Whisper и GigaAM.
- При изменении модели или encoder повышать cache fingerprint.
- Не отключать VAD-gated planner без измерения RAM/RTF.
- Если пользователь сообщает о трёх соседних блоках одного человека, сначала проверять speaker ID и gap, а не менять ASR.
- Если нужен side-by-side исторический 2.0/3.0, собирать отдельные архивные source snapshots с новыми application ID; текущие comparison APK 4.0/4.1 уже устанавливаются параллельно.

## 9. Полный инвентарь deliverables

В папке `deliverables` сохранены и старые, и новые сборки; старые варианты не удалялись:

```text
LocalVoices-2.0-debug.apk
LocalVoices-3.0-all-in-one.apk
LocalVoices-3.1-one-sample.apk
LocalVoices-4.0-Whisper.apk
LocalVoices-4.0-GigaAM.apk
LocalVoices-Optimized-Whisper-debug.apk
LocalVoices-Optimized-GigaAM-debug.apk
LocalVoices-ExperimentalCascade-Whisper-debug.apk
LocalVoices-ExperimentalCascade-GigaAM-debug.apk
LocalVoices-4.1-Whisper.apk
LocalVoices-4.1-GigaAM.apk
LocalVoices-4.0-Optimized-Whisper.apk
LocalVoices-4.0-Optimized-GigaAM.apk
LocalVoices-4.1-Cascade-Whisper.apk
LocalVoices-4.1-Cascade-GigaAM.apk
```

Уникальные package ID новых приложений:

```text
ru.localvoices.v41.whisper
ru.localvoices.v41.gigaam
ru.localvoices.v40.optimized.whisper
ru.localvoices.v40.optimized.gigaam
ru.localvoices.v41.cascade.whisper
ru.localvoices.v41.cascade.gigaam
```

SHA-256 проверенных 4.1 APK:

```text
LocalVoices-4.1-GigaAM.apk          D009AF059F6CD9658054F040FA84EB69E2F1D8E730B7AF291CCDDE0FE00487CA
LocalVoices-4.1-Cascade-GigaAM.apk  DCEB42555E3864B00BF42390C2B1F24C8471D97DC2467DB7F35E456F2D5105F1
LocalVoices-4.1-Whisper.apk         7931AF0C8DF4E7A9532420479FB9A8CD2D1322665500EBB7DB2DA197B20F4886
LocalVoices-4.1-Cascade-Whisper.apk ADF929BEA722271925DE442236715785312F55890D8C13BBCC046468A590BABC
```

## 10. Карта фактических изменений исходников

- `app/src/main/java/ru/localvoices/data/Preferences.kt` — значение экспериментального каскада по умолчанию теперь задаётся flavor BuildConfig.
- `app/src/main/java/ru/localvoices/audio/CaptureService.kt` — backend-specific live preview: GigaAM стартует раньше, Whisper использует отдельный размер окна; preview корректно отменяется при остановке.
- `app/build.gradle.kts` — шесть installable flavors, уникальные application ID/labels, backend flags, cascade defaults и включение GGUF-модели GigaAM в каждый GigaAM flavor.
- `core/src/main/kotlin/ru/localvoices/core/Diarizer.kt` — coalesce соседних подтверждённых блоков одного speaker с ограничением gap 1,25 с; неизвестные кластеры не склеиваются только по одинаковому display name.
- `core/src/main/kotlin/ru/localvoices/core/Domain.kt` — `SpeechWindowPlanner`, пересечение акустической сегментации с VAD и планирование окон только на речевых участках.
- `app/src/main/java/ru/localvoices/processing/ProcessWorker.kt` — speaker embeddings запускаются через VAD-gated planner; ASR-текст от этого не отбрасывается.
- `core/src/test/kotlin/ru/localvoices/core/AlgorithmsTest.kt` — добавлены тесты склейки, защиты неизвестных кластеров и пропуска тишины; всего успешных core-тестов: 29.
- `README.md`, `IMPLEMENTATION_REPORT.md`, `APK_VERIFICATION.md`, `EXPERIMENTAL_CASCADE_UPDATE.md`, `VERSION_MATRIX_4_1.md` — обновлены описания архитектуры, сборок и способов проверки.

## 11. Ограничения, которые нельзя выдавать за готовые измерения

Сейчас доказано успешное построение APK и прохождение unit/core-тестов, но ещё не доказаны корпусные WER/CER, DER/JER, реальный RTF и peak RAM на ARM64. Поэтому следующий разработчик должен добавить воспроизводимый benchmark-набор: одинаковые аудио, reference transcript, reference speakers, время, память, температуру и результат каждого flavor. Нельзя считать эмулятор заменой измерения на целевом телефоне.
