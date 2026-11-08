# Единый журнал исследования «Локальных голосов»

Дата: 7 ноября 2026. Этот файл объединяет все технические находки, проверки, гипотезы и варианты ускорения, полученные в текущей работе. Он описывает исследование и план экспериментов. В рамках этой работы исходный код приложения не изменялся и APK не собирался.

## 1. Короткий результат

Главная проблема текущей цепочки состоит не в одной неисправной модели. Есть несколько независимых слоёв:

1. GigaAM выдаёт CTC/SentencePiece pieces, а Android-слой местами трактует их как готовые слова.
2. Самостоятельный токен-разделитель `▁` превращается в пробел, а затем удаляется фильтром `isNotBlank()`.
3. Говорящий назначается каждому piece до восстановления целого слова. Поэтому одно слово может быть разделено между репликами.
4. Текущий native-путь передаёт raw pieces и короткие emission-времена, но не полноценные word intervals и не достоверную confidence.
5. Диаризация и эмбеддинги частично вычисляются повторно, а неизвестный/известный голос смешаны с задачей кластеризации.
6. Все тяжёлые стадии могут работать на участках, где нет речи, хотя дешёвый каскад способен заранее отсеять большую часть тишины, музыки и очевидного шума.

Лучшая общая идея: сделать каскад. Сначала дешёвая акустическая карта и грубый фильтр, затем ASR и speaker embedding только на участках с речью, затем повторный дорогой проход только на фрагментах с конфликтами или низкой уверенностью.

## 2. Что проверено в локальном коде

Проверены `ProcessWorker.kt`, `Diarizer.kt`, `Hdbscan.kt`, `Audio.kt`, `AcousticSegmenter.kt`, `GigaAmEngine.kt`, `PeopleRepository.kt`, native GigaAM bridge и закреплённая версия transcribe.cpp.

### 2.1. Потеря пробелов

В `app/src/main/java/ru/localvoices/ml/GigaAmEngine.kt` ведущий `▁` заменяется на ASCII-пробел. Для самостоятельного ID 1 это строка из одного пробела. В `ProcessWorker.kt` затем выполняется фильтрация `decoded.filter { it.text.isNotBlank() }`, поэтому этот boundary token исчезает.

Префиксные pieces вроде `▁по` не исчезают, потому что содержат буквы. Поэтому склейка проявляется не на каждом слове.

Синтетическая проверка на действительных ID текущего GGUF:

```text
До фильтра:        Здравствуйте. Здравствуйте. D
После фильтра:     Здравствуйте.Здравствуйте.D
```

Это детерминированное воспроизведение преобразований, а не inference на пользовательском аудио. Буква D была специально включена во вход, поэтому тест доказывает потерю пробела, но не источник D.

### 2.2. Разрезание слов

GigaAM возвращает `AsrUnit` на каждый SentencePiece piece. `TranscriptMerger` назначает speaker отдельно каждому unit, а потом объединяет только элементы с одинаковым speaker ID/name и небольшим промежутком. Если граница speaker timeline попала внутрь многотокенного слова, части могут попасть в разные реплики.

Например, действующий словарь допускает представление «пожалуйста» как `▁по + ж + а + л + у + й + ста`. Такие pieces должны сначала стать одним `Word`, и только затем Word следует связывать с speaker timeline.

Простое увеличение порога 0,7 секунды не исправляет архитектуру: emission-time не равен паузе между словами, а смена говорящего может попасть внутрь слова.

### 2.3. Кэш и лишние вычисления

В `ProcessWorker` сначала вычисляется `decoded`, а затем выбирается `cache.asr(index) ?: decoded`. Значит, кэш не предотвращает дорогой inference. Проверять и валидировать кэш надо до создания PCM-блоков и вызова модели.

### 2.4. Двойная диаризация

`AcousticSegmenter` уже выполняет segmentation/speaker embedding, после чего worker снова извлекает WeSpeaker-эмбеддинги и кластеризует окна. Это потенциальное повторное вычисление. Нужно либо использовать segmentation-only выход, либо оставить одну реализацию диаризации и измерить стоимость каждой стадии.

### 2.5. Cluster-first с исключением anchors

Очень уверенные совпадения с известным профилем становятся anchors и исключаются из массива unknown для HDBSCAN. Так чужой голос, ошибочно похожий на известного человека, может быть убран из общего кластерного анализа ещё до проверки структуры записи. Надёжнее сначала кластеризовать все качественные окна анонимно, а затем сопоставлять кластеры с людьми.

### 2.6. HDBSCAN и память

При 2,5-секундных окнах и шаге 1,25 секунды число окон быстро растёт. Нижняя треугольная матрица Double для примерно 3000 окон занимает около 36 MB только на расстояния. Для длинных файлов нужны representative samples, потоковое присоединение окон и ограничение памяти. Кластеризовать всю часовую запись плотной матрицей нельзя считать масштабируемой стратегией.

### 2.7. Аудио

Стерео сейчас усредняется. Это уничтожает пользу раздельных сторон звонка и при противофазе может ослабить речь. Ресемплинг линейный и без явного полосоограничивающего FIR/polyphase фильтра, что может давать алиасинг. Каналы и исходные sample indices нужно сохранять.

### 2.8. Native GigaAM

Проверенная версия transcribe.cpp: `5700f127e38dd53c4735ea23ef743b4b538eacd4`.

- CTC blank равен `n_classes - 1`.
- В GGUF 257 классов, 256 SentencePiece pieces и blank ID 256.
- Greedy collapse: повтор пропускается только при совпадении с предыдущим исходным label; blank обновляет `prev_label`, но не добавляется в output.
- `[e,e,blank,e]` даёт `[e,e]`, а `[e,e,e]` даёт `[e]`.
- Для полного текста native вызывает `tok.decode(all_ids)`.
- Для token entries native передаёт `tok.token(id)`, то есть raw piece, а не фрагмент полного декодированного текста.
- Время token entry сейчас `frame * 40 ms` и `+40 ms`. Это время emission frame, а не длительность subword.
- Longform сшивает encoder frames до CTC, а не просто склеивает два готовых текста.
- Byte fallback поддержан в runtime, но в фактическом GGUF не найдено `<0xHH>` pieces.

Критическая поправка: `TokenEntry.p` имеет default `0.0f`, а GigaAM decoder его не заполняет. Сейчас это не настоящая confidence. Нельзя использовать нулевое поле для отбора плохого текста.

### 2.9. UI и пунктуация

В GigaAM-пути Qwen punctuation не вызывается: для backend GigaAM создаётся `PunctuationResult(rawResult, 0, 0)`. В UI найдено `row.text.trim()`. Отдельного доказанного преобразования точки в слово «Точка» или вставки D/V в Kotlin не найдено.

`rawAsr` хранится уже после фильтрации. Старый кэш с потерянными boundary token нельзя надёжно исправить только повторным merge; требуется версия reconstruction в cache key и выборочный повтор ASR.

## 3. Сравнение с официальной GigaAM

Официальный Python-декодер использует SentencePiece sequence decode для полного текста. `timestamps_utils.py` содержит `frames_to_words`: pieces накапливаются до следующего `▁` или ASCII-пробела, затем создаётся слово с первым frame и последним frame + 1.

Официальные времена — приблизительные emission-based границы. Они не являются forced alignment и не доказывают, что слово действительно распознано правильно. Переносить нужно сам принцип «pieces → Word → speaker», но с явным контрактом для unknown, control tokens, byte fallback, punctuation и overlap.

Ссылки: [GigaAM decoding.py](https://github.com/salute-developers/GigaAM/blob/main/gigaam/decoding.py), [timestamps_utils.py](https://github.com/salute-developers/GigaAM/blob/main/gigaam/timestamps_utils.py), [model.py](https://github.com/salute-developers/GigaAM/blob/main/gigaam/model.py).

## 4. Полный список обнаруженных дефектов и рисков

- ASR вызывается до проверки кэша.
- Segmentation/embedding вычисляются потенциально дважды.
- Known anchors исключаются из глобального unknown clustering.
- HDBSCAN плохо масштабируется по числу окон.
- Стереоканалы теряются.
- Ресемплинг не гарантирует anti-aliasing.
- CTC pieces воспринимаются как слова.
- Самостоятельный `▁` удаляется.
- Speaker attribution происходит до word reconstruction.
- Token timestamps используются как физические границы речи.
- `p` выглядит как confidence, но фактически равен нулю.
- GigaAM и embedding model живут одновременно, увеличивая RAM.
- GigaAM cancellation неполна внутри native inference.
- Outlier HDBSCAN ошибочно трактуется как шум, хотя это может быть короткая реплика, новый человек или overlap.
- PeopleRepository может перезаписывать профиль человека новым centroid текущей сессии.
- Одно лицо/человек не должен быть одним единственным усреднённым embedding.
- Неизвестные speakers и noise должны быть разными сущностями.
- 60 минут и до 3000 окон требуют явной политики ограничений.

## 5. Базовая правильная архитектура

```text
Оригинальный файл
  → декодирование каналов и sample-таймлайн
  → дешёвая акустическая карта
  → ASR / анонимная диаризация
  → Word reconstruction
  → speaker timeline и overlap
  → identity matching
  → выборочная очередь уточнений
  → JSON/SRT/TXT экспорт
```

Сущности должны быть раздельными: `AudioAsset`, `ChannelTrack`, `AcousticFrameBlock`, `AsrBlock`, `Word`, `SpeakerSegment`, `SpeakerPrototype`, `IdentityHypothesis`, `RefinementJob`, `ProcessingRun`.

`Word` хранит text, sample start/end, token IDs, provenance, text confidence, timing confidence и flags риска. Speaker segment может иметь несколько активных speakers. `person_id` нельзя использовать вместо локального `speaker_id`.

## 6. Идея пользователя: дешёвая проверка «речь/шум/осмысленность»

Идея правильная, но её следует сделать каскадом, а не одним бинарным правилом.

### Слой A: почти бесплатный отбор

Для 10–32 ms кадров вычислять RMS/энергию, zero-crossing, spectral flux, долю энергии в полосе речи, clipping и stationary noise floor. Удалять только явную цифровую тишину и повреждённые блоки. Энергетический порог использовать адаптивный, потому что тихий голос может быть ниже среднего уровня.

Для MP3/AAC можно дополнительно читать packet/frame metadata и избегать полного декодирования длинных участков, если формат даёт безопасную информацию о пустых/повреждённых кадрах. Это оптимизация формата, но не универсальный VAD: для точного решения всё равно нужен PCM на пограничных местах.

### Слой B: очень дешёвый VAD

Silero VAD в ONNX работает на 8/16 kHz фиксированными примерно 32 ms окнами и обычно дешевле 1 ms на окно на одном CPU-потоке по данным проекта. Он должен объединяться с hangover 50–150 ms, чтобы не обрезать начало/конец слов. Ссылки: [Silero VAD](https://github.com/snakers4/silero-vad), [FAQ о скорости ONNX](https://github.com/snakers4/silero-vad/wiki/FAQ).

Выгодная схема: сначала energy gate, затем Silero только на подозрительных кадрах; непрерывные области с высокой вероятностью речи передавать дальше, явную тишину не пропускать через ASR.

### Слой C: классификация события

Дешёвый audio event classifier разделяет `speech`, `music`, `laugh`, `cough`, `keyboard`, `wind`, `vehicle`, `unknown`. Он не должен сам удалять фрагмент: его задача — маршрутизация. Подозрительные фрагменты с музыкой или шумом можно пропускать через ASR только один раз на коротком контексте.

### Слой D: ASR risk score

После дешёвого CTC-прохода оценивать blank ratio, margin top-1/top-2, entropy, длину текста, долю `<unk>`, повторяющиеся символы, странные переходы латиница/кириллица и стабильность при ±0,5–1 с изменении окна. Только высокий risk отправлять на второй проход.

## 7. Все найденные способы ускорения

### 7.1. Экономия на объёме аудио

- Пропуск явной тишины и длинных пауз.
- Слияние соседних speech islands в блоки 15–25 секунд.
- ASR только на VAD speech duration, но с сохранением исходных offset.
- Малый контекст вокруг каждого островка; не физически склеивать аудио без таблицы соответствия времени.
- Не запускать enhancement, speaker embedding и punctuation на non-speech.
- Плотный проход только вокруг границ VAD/speaker change; монологи обрабатывать реже.

### 7.2. Адаптивная плотность speaker embeddings

Текущие 2,5 с / 1,25 с — зависимые окна. В длинном чистом монологе можно начинать с 4–8 с шага и вычислять embedding только при speaker-change posterior, резком изменении качества, overlap, конфликте с профилем или через ограниченный heartbeat. В коротких репликах и на стыках оставить 0,5–1,5 с окна.

Идея core samples selection: хранить небольшой набор representative embeddings, обновлять его по времени и разнообразию, а не держать все окна. [Online diarization with core samples](https://www.isca-archive.org/interspeech_2022/yue22b_interspeech.html).

Twofold sliding window позволяет извлекать frame-level признаки на неперекрывающихся коротких сегментах, а затем формировать несколько overlapped embeddings с меньшим числом повторных проходов. [Efficient speaker embedding extraction](https://www.isca-archive.org/interspeech_2024/choi24d_interspeech.html).

### 7.3. Иерархическая кластеризация

Сначала кластеризовать representative set, затем присоединять остальные окна nearest prototype с порогом отказа. Не запускать полную квадратичную HDBSCAN-матрицу для каждого часа. Для длинных файлов использовать online centroid/HMM/Viterbi или bounded-memory VBx-подобную модель.

VBx полезен как Bayesian HMM baseline для temporal smoothing, но нельзя называть произвольный cosine+HMM полноценным VBx без соответствующего PLDA backend. [VBx](https://github.com/BUTSpeechFIT/VBx).

NME spectral clustering с оценкой числа кластеров и SC-pNA с прореживанием affinity — кандидаты для ограниченного representative set, не для бездумного запуска на всей записи.

### 7.4. Каскад моделей

Вместо одного тяжёлого extractor использовать B0/B1 дешёвый сканер и B2/B3 только для конфликтных фрагментов. Кандидаты: [ReDimNet2](https://github.com/PalabraAI/redimnet2), [CAM++](https://arxiv.org/abs/2303.00332), WeSpeaker baseline. Embeddings разных моделей нельзя сравнивать между собой без отдельных прототипов и калибровки.

Для ASR сохранять GigaAM как основной русскоязычный baseline, а альтернативы включать только на risk-фрагментах: Whisper, Parakeet-TDT, Qwen3-ASR/ForcedAligner, sherpa-onnx Russian CTC/Zipformer. [sherpa-onnx models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/), [Russian GigaAM models in sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

### 7.5. Второй проход только при сомнении

Очередь refinement должна иметь конечный бюджет. Приоритет: стыки ASR-блоков, конфликт известного профиля, overlap, короткая реплика, высокий entropy/blank-risk, повторный результат которого нестабилен.

На один фрагмент максимум два дорогих уточнения. Если они не дают устойчивого результата, выводить `uncertain`, а не запускать бесконечные повторы. Начальные продуктовые бюджеты: Balanced 10–20% speech duration, Accurate до 40%, но их нужно калибровать.

### 7.6. Дешёвый decoder-only повтор

Не всегда нужен второй encoder. Если native сохранит logits или top-k для risk-фреймов, можно повторить CTC collapse/beam/lexicon scoring на уже вычисленных данных. Полезны: entropy, top-1/top-2 margin, blank ratio, token stability и словарь терминов. Это особенно важно для ошибок D/V и пунктуации.

### 7.7. Early exit и пропуск кадров

Опубликована схема CTC blank-triggered dynamic layer skipping: промежуточный blank с высокой вероятностью позволяет пропустить последние encoder layers для неинформативных кадров; в работе сообщается около 29% ускорения с небольшой потерей качества. Это не готовая настройка текущего GigaAM: потребуется промежуточная CTC-head, изменение графа и, вероятно, дообучение/knowledge distillation. [Paper](https://arxiv.org/abs/2401.02046).

Blank-guided frame skipping в transducer-системах показывает более агрессивный вариант, но для текущего CTC нужно сначала проверить, можно ли безопасно пропускать только соседние высокоblank участки. Нельзя удалять frames до encoder без калиброванной модели.

### 7.8. Token merging и downsampling

FastConformer использует 8x subsampling и сообщает заметное ускорение относительно обычного Conformer; Zipformer меняет структуру encoder, чтобы средние стеки работали на меньшей частоте. Это идеи для выбора другой модели, а не локальная оптимизация уже конвертированного GigaAM. [FastConformer](https://arxiv.org/abs/2305.05084), [Zipformer](https://arxiv.org/abs/2310.11230).

Adjacent Token Merging и более новые token-merging работы уменьшают число похожих encoder tokens; опубликованные результаты доходят до существенного уменьшения токенов и ускорения, но это требует изменения architecture/inference и проверки на русском CTC. [A-ToMe](https://arxiv.org/abs/2306.16009), [multilingual token merging](https://arxiv.org/abs/2609.13151).

### 7.9. Выборочный denoise

RNNoise, DeepFilterNet и лёгкий spectral suppression запускать только на участках, где акустическая карта показывает шум, clipping или низкий SNR. Всегда сохранять original как reference и сравнивать ASR на original/enhanced. Деноизер может ухудшить тембр и speaker embedding, поэтому enrollment по умолчанию брать с оригинала. [RNNoise](https://github.com/xiph/rnnoise).

### 7.10. Разделение overlap только локально

Separator и отдельный ASR запускать только на overlap intervals, а не на всей записи. Начальная версия — не более двух источников. Выходы сопоставлять с чистыми speaker embeddings, а не по номеру separator output. Если разделение неустойчиво, сохранять `overlap_unresolved`.

### 7.11. Повторное использование результатов

- Проверять ASR cache до PCM/inference.
- Кэшировать mel/encoder только при измеренной выгоде и ограниченном диске.
- Ключ кэша должен включать hash PCM, sample range, model hash, decoder settings, resampler/preprocessing version.
- Переименование человека не инвалидирует ASR/embeddings.
- Изменение speaker threshold не требует повторного encoder.
- UI/export не должны заново запускать model.
- Для process death сохранять checkpoints по `AsrBlock`, `EmbeddingBlock`, `DiarizationRun`.

### 7.12. Параллелизм и память Android

- Не запускать одновременно несколько тяжёлых encoders на одних и тех же big cores.
- Параллелить только независимые дешёвые стадии и блоки, если RAM позволяет.
- Переиспользовать native buffers и избегать лишних JNI копий.
- Освобождать GigaAM до массового извлечения embeddings, если повторный ASR не нужен.
- Использовать bounded queues и backpressure.
- Проверить 1/2/4 потока на конкретном ARM64 устройстве, а не выбирать число по количеству CPU.
- Измерять cold start, warm start, peak PSS, thermal throttling, wall time и последнюю пятиминутную скорость.

ONNX Runtime поддерживает graph optimization, настройку intra/inter-op threads и управление spinning; spinning ускоряет короткие вызовы, но расходует CPU. На мобильном нужно тестировать `intra_op_num_threads`, отключение spin между блоками и один общий thread pool. [ONNX threading](https://onnxruntime.ai/docs/performance/tune-performance/threading.html), [graph optimizations](https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html).

Q4_K_M уменьшает размер, но не гарантирует скорость относительно Q8. Нужно сравнивать Q4/Q5/Q8 и FP16 на целевых телефонах. Современные ARM/ggml fast paths и KleidiAI могут помогать только при поддержке конкретного устройства и версии runtime. [ggml releases](https://github.com/ggml-org/ggml/releases).

ONNX INT8/NPU может быть быстрее, но quantization/dequantization иногда добавляет overhead; QNN HTP требует quantized model, fixed shapes и имеет ограничения операторов. Нельзя считать NPU ускорением без on-device benchmark. [ONNX quantization](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html), [QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html).

Android Thermal API/ADPF нужно учитывать: длительный inference может замедлиться из-за нагрева. Бенчмарк без контроля температуры вводит в заблуждение. При перегреве снизить параллелизм/качество refinement и продолжить с checkpoints. [Android Thermal API](https://developer.android.com/games/optimize/adpf/thermal).

## 8. Новые модели и научные направления

- CAM++: экономичный speaker extractor.
- ReDimNet2 B0/B1: дешёвый сканер; старшие варианты — verifier.
- Sortformer/Streaming Sortformer: прямые speaker slots и перенос состояния между блоками.
- Nemotron-3-Diarization: исследовательский direct diarizer; Android/RAM не подтверждены.
- MSDD: короткий контекст для смены, длинный для устойчивости голоса.
- VBx: temporal HMM smoothing и online memory.
- NME spectral clustering/SC-pNA: альтернативы HDBSCAN на representative set.
- DiCoW/SE-DiCoW: target-speaker ASR для overlap; тяжёлая отдельная ветка.
- Parakeet-TDT v3: независимый русский ASR-кандидат с timestamps.
- Qwen3-ASR/ForcedAligner: reviewer и aligner, но дороже и не равен текущей Qwen punctuation.
- sherpa-onnx Zipformer/GigaAM ONNX: готовые Android/ONNX backend-кандидаты.
- pyannote/Community-1 и SUPlime: teacher/desktop baseline; опубликованные серверные DER не переносятся на Android.
- HuBERT early exit: подтверждает общий принцип динамического выхода, но не является готовой GigaAM-опцией. [HuBERT-EE](https://www.isca-archive.org/interspeech_2024/yoon24b_interspeech.html).

## 9. Идентичность людей и защита от ошибочного присвоения

Хранить отдельно `recording_id`, `local_speaker_id`, `person_id`, `identity_candidate`, `decision_source`, `user_confirmed`.

Сначала строить анонимные кластеры по всем качественным одиночным окнам, включая окна с сильным совпадением с известным человеком. Потом сопоставлять cluster prototypes с enrollment. Ошибка известного совпадения не должна удалить чужой голос из кластерного анализа.

Enrollment не должен перезаписываться последним centroid. Сохранять исходный длинный образец и несколько прототипов: чистый, телефонный, дальний, подтверждённые сессии. Неподтверждённые автоматические решения не должны обучать профиль.

Порог личности учитывать по cosine/PLDA score, margin до impostor, внутрикластерной дисперсии, уникальной speech duration, качеству канала и числу независимых участков. Оценивать open-set FAR/FRR, а не только EER на закрытом списке.

## 10. Overlap, шум и отказ

VAD, overlap detection и separation — разные задачи. Noise/outlier не является отдельным speaker. Смех/кашель/клавиатура должны маркироваться как non-speech event, а не принудительно превращаться в текст или имя.

Для overlap хранить оба activity track, включать separator локально, а при неуверенности показывать unresolved. Для неразборчивой речи разрешён отказ. Оценивать risk–coverage: нельзя улучшить WER простым удалением всех плохих участков.

## 11. Времена и текст

Пайплайн должен быть `CTC frame labels → SentencePiece pieces → Word objects → speaker attribution → turns`. Punctuation не получает искусственную длительность и не должен сам создавать speaker change.

Для точности сохранять logits/top-k и выполнять forced alignment только для принятых или спорных слов. Начальные frame times использовать как приближение. Все времена хранить через integer sample indices и один offset; не накапливать округлённые секунды.

Граница ASR блока, VAD, speaker change и предложение — разные типы событий. JSON должен хранить их отдельно. SRT недостаточен для overlap, confidence и provenance.

## 12. Шесть сценариев и специальные ускорения

| Сценарий | Что ускорять и проверять |
|---|---|
| Студийный подкаст | редкие embeddings в длинном монологе, плотный ASR только на речи, denoise обычно выключен |
| Интервью одним микрофоном | многомасштабные окна, короткие ответы не отбрасывать, локальный retry на сменах |
| Mono-созвон | VAD и codec-aware thresholds, не считать codec jump новым speaker |
| Узкополосный телефон | качественный 8→16 kHz resample, отдельная калибровка профилей и low-band quality gate |
| Шумный смартфон | original/enhanced A/B только на проблемных сегментах, выборочный RNNoise |
| Многоканальная запись | анализ каналов до mono downmix, ASR по активному каналу, cross-talk correction |

## 13. Воспроизводимый план измерений

Минимальный корпус: студия, интервью, mono-созвон, телефон, шумный смартфон, multi-channel; по несколько файлов, 1/2/4+ speakers, похожие голоса, overlap и короткие ответы. Development/test разделить по людям и записям.

Измерять: WER/CER, punctuation F1, DER/JER с collar/overlap policy, speaker-attributed WER/cpWER, ошибка числа людей, recall коротких реплик, FAR/FRR/open-set identification, median/P90 word boundary error, risk–coverage.

Для скорости измерять wall-time / full duration, wall-time / speech duration, peak PSS, размер кэша, cold/warm load, время каждой стадии, temperature/thermal status, battery/energy если доступно, throughput в начале и конце длинной записи.

Порядок ablation:

1. cache-before-inference;
2. устранение двойного extractor;
3. сохранение каналов и FIR/polyphase resampling;
4. исправление boundary token и Word reconstruction;
5. дешёвый energy+Silero gate;
6. адаптивная плотность embeddings и representative clustering;
7. независимые enrollment prototypes и late identity matching;
8. CTC risk score и decoder-only retry;
9. selective denoise;
10. selective overlap/separation;
11. сравнение Q4/Q8/INT8/ONNX/XNNPACK/NPU;
12. сравнение новых ASR и diarization backends.

Каждый этап менять отдельно. Иначе невозможно определить, что реально ускорило приложение или испортило WER.

## 14. Тесты, которые обязательны до оптимизации

- отдельный `▁` и prefix `▁слово`;
- продолжения слова без `▁`;
- пустая последовательность и leading/trailing boundary;
- пунктуация, кавычки, латиница, цифры, `<unk>`;
- CTC `A-A-blank-A`;
- два одинаковых слова одним speaker;
- два разных speakers с одинаковым словом;
- смена speaker внутри многотокенного слова;
- punctuation после speaker change;
- split одного слова на ASR seam;
- overlap, музыка и шум;
- 44.1/48 kHz import и 25+ секунд;
- повторный экспорт не изменяет слова;
- перестановка имён не меняет текст;
- все pieces сохраняют provenance;
- global time получается ровно одним offset;
- chunk overlap не дублирует слова.

## 15. Что нельзя делать как «быстрое исправление»

Нельзя удалять все D/V или всю латиницу, вставлять пробел между каждым токеном, назначать speaker каждому символу, использовать пунктуацию как доказательство смены человека, объединять всех unknown в одного, заменять весь текст LLM, считать нулевой `p` confidence или бесконечно повторять плохой участок.

## 16. Приоритет действий

Первый практический слой: сохранить boundary token, ввести Word reconstruction до diarization, исправить cache-before-inference, вернуть native IDs/full_text для диагностики и освободить GigaAM до embeddings.

Второй слой: energy + Silero gate, speech islands, adaptive embedding schedule, representative clustering, bounded refinement queue.

Третий слой: отдельные enrollment prototypes, HMM/Viterbi smoothing, CTC risk/decoder retry, selective denoise и overlap.

Четвёртый слой: новые модели, ONNX/XNNPACK/NPU, early exit, token merging, direct diarization и teacher-student перенос.

Самые быстрые вероятные выигрыши — не замена всей модели, а пропуск тишины, устранение повторного extractor, корректный кэш, освобождение RAM, уменьшение плотности embeddings и повторный дорогой проход только для 10–20% спорных речевых участков.

## 17. Ограничения доказательств

Реальный проблемный audio sample не прогонялся в полном native+Android трассировочном режиме. Полное сравнение native `full_text` с Python GigaAM, WER, DER, thermal benchmark и on-device сравнение Q4/Q8/ONNX пока не выполнены. Поэтому происхождение конкретных D/V не доказано; доказаны потеря самостоятельного `▁`, возможность разрезания words и отсутствие реальной confidence в поле `p`.

Этот файл — единый исследовательский журнал и список инженерных решений. Он не утверждает, что все перечисленные модели быстрее на данном телефоне: каждую альтернативу нужно проверять на одном корпусе и с одинаковыми метриками.

