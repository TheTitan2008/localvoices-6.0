# Additive experimental cascade

Дата: 7 ноября 2026.

Это отдельный слой поверх существующего конвейера. Старый путь не удалён: настройка выключена по умолчанию, а прежние APK сохранены без перезаписи.

## Что добавлено

- `Preferences.experimentalCascade` и переключатель «Экспериментальный каскад» в расширенных настройках.
- Отдельный cache fingerprint (`v6 ... cascade=...`), поэтому смена режима не смешивает результаты двух конфигураций.
- Speaker-window planner пересекает acoustic segmentation с Silero VAD и не передаёт длинные паузы в encoder.
- Дешёвый quality gate перед speaker-encoder: пропускаются только очевидно непригодные окна (почти нулевая энергия или сильный clipping); тихая речь не отбрасывается по одному порогу.
- Такой же первый gate стоит перед ASR: только VAD-фрагмент с практически нулевой RMS-энергией может быть отброшен без вызова модели; результат кэшируется как пустой, чтобы повторный запуск не вызывал ASR снова.
- Адаптивное прореживание эмбеддингов для чистых речевых участков длительностью от 8 секунд: окно 3,0 с и шаг 3,75 с вместо 2,5/1,25 с. Короткие фразы, переходы и участки с overlap используют прежний плотный режим.
- Новой режим выпускается отдельными файлами:
  - `deliverables/LocalVoices-ExperimentalCascade-Whisper-debug.apk`
  - `deliverables/LocalVoices-ExperimentalCascade-GigaAM-debug.apk`

Для параллельной установки доступны новые именованные сборки с отдельными package ID: [VERSION_MATRIX_4_1.md](VERSION_MATRIX_4_1.md).

## Почему именно такой компромисс

Официальная реализация GigaAM уже отдаёт текст вместе с временными границами слов, поэтому каскад не должен повторно прогонять пунктуацию или выдумывать границы после ASR: [GigaAM model.py](https://github.com/salute-developers/GigaAM/blob/main/gigaam/model.py).

Для длинных стабильных участков применён принцип representative/core sampling из потоковой диаризации: сначала плотные окна на сложных местах, а на однородных участках — более редкие репрезентативные образцы, чтобы сократить число дорогих speaker-inference операций: [Online diarization with core samples](https://www.isca-archive.org/interspeech_2022/yue22b_interspeech.html).

Настройка оставлена экспериментальной, потому что фактическое ускорение зависит от доли длинных чистых монологов, телефона и thermal throttling. На шумных, перекрывающихся и коротких фразах плотность сохраняется.

## Проверка

- `:core:test` — BUILD SUCCESSFUL, 25 тестов.
- `:app:assembleWhisperDebug :app:assembleGigaamDebug` — BUILD SUCCESSFUL.
- Новые SHA-256:
  - Whisper: `9A8539CF0391CAEA49B5B853E3A1FC060278FAEA4DC3A51A22827C9741B88D46`
  - GigaAM: `522FED59CA51DDCCD5DA6788970679DE5C99E37116A15E55440146950F31942C`
- Manifest проверен: `ru.localvoices` и `ru.localvoices.gigaam`; модели встроены в APK.

Полный real-time factor, WER/DER и peak RAM пока нельзя заявлять без одинакового аудиотеста на устройстве: desktop JNI CLI и Android profiler в окружении отсутствуют.
