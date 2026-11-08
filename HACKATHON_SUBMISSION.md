# Комплект сдачи решения LocalVoices 6.0

Этот файл сопоставляет официальный список материалов с конкретными файлами проекта.

## 1. APK-файл приложения

Готовый проверенный APK опубликован в GitHub Release:

- `LocalVoices-6.0-GigaAM-MP3-Fixed.apk`
- размер: около 897 МБ;
- модели встроены в APK;
- обработка выполняется локально.

Ссылка: https://github.com/TheTitan2008/localvoices-6.0/releases/tag/submission-6.0

Прямая ссылка на APK: https://github.com/TheTitan2008/localvoices-6.0/releases/download/submission-6.0/LocalVoices-6.0-GigaAM-MP3-Fixed.apk

## 2. Исходный код проекта

Исходники Android находятся в репозитории:

https://github.com/TheTitan2008/localvoices-6.0

В проекте есть модули `app`, `core`, `gigaam`, `llm`, Kotlin-код, JNI/C++, тесты и Gradle-конфигурация.

## 3. Инструкция по установке и использованию

- https://github.com/TheTitan2008/localvoices-6.0/blob/main/INSTALL.md
- https://github.com/TheTitan2008/localvoices-6.0/blob/main/README.md

В инструкции описаны два сценария: импорт MP3 и запись разговора через встроенный диктофон.

## 4. Архитектура и применяемые модели

- https://github.com/TheTitan2008/localvoices-6.0/blob/main/docs/ARCHITECTURE.md
- https://github.com/TheTitan2008/localvoices-6.0/blob/main/docs/PROJECT_ANNOTATION.md
- https://github.com/TheTitan2008/localvoices-6.0/blob/main/docs/PROTOCOL.md

Описаны MediaCodec, PCM 16 кГц, Silero VAD, GigaAM ASR, speaker embeddings, HDBSCAN, локальная LLM и live-кэш записи.

## 5. Ограничения решения

- https://github.com/TheTitan2008/localvoices-6.0/blob/main/docs/LIMITATIONS.md
- https://github.com/TheTitan2008/localvoices-6.0/blob/main/LIMITATIONS.md

## 6. Результаты собственных тестов

- https://github.com/TheTitan2008/localvoices-6.0/blob/main/docs/TEST_RESULTS.md
- https://github.com/TheTitan2008/localvoices-6.0/blob/main/TEST_RESULTS.md
- https://github.com/TheTitan2008/localvoices-6.0/blob/main/artifacts/test-results/results.csv
- https://github.com/TheTitan2008/localvoices-6.0/blob/main/APK_VERIFICATION.md

В отчёте отдельно объясняется разница между временем декодирования MP3 и временем ASR/диаризации.

## 7. Видеодемонстрация

Сценарий видео и место для материала:

- https://github.com/TheTitan2008/localvoices-6.0/blob/main/artifacts/demo/VIDEO_SCRIPT.md
- https://github.com/TheTitan2008/localvoices-6.0/tree/main/artifacts/demo

Рекомендуемая длительность — 3–5 минут: запуск APK, импорт MP3, обработка, говорящие, таймкоды, экспорт JSON и короткий показ записи через диктофон. Сам файл видео нужно добавить в эту папку или передать отдельной ссылкой, если он хранится на внешнем видеохостинге.

## Форма сдачи

До 09:00 нужно отправить материалы через форму организаторов:

https://forms.yandex.ru/u/6aedea5f6f1ca49998bfe7e

В форму удобно вставить ссылку на репозиторий и ссылку на Release с APK.
