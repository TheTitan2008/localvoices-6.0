# Сравнение speaker extractor-кандидатов

| Extractor | Роль | Статус |
|---|---|---|
| WeSpeaker / `speaker.onnx` | Основной текущий extractor | Используется, требует on-device benchmark |
| Whisper pooling | Экспериментальный адаптер | Не считать обученным speaker recognizer |
| CAM++ | Более лёгкий context-aware extractor | Кандидат для дешёвого scanner-а |
| ReDimNet2 B0/B1 | Малый mobile extractor | Кандидат для selective verification |
| ReDimNet2 B2/B3 | Более точный verifier | Включать только на спорных участках |
| VBx/HMM | Temporal smoothing backend | Не extractor; требует калибровки embeddings |

Embeddings разных моделей нельзя смешивать в одном cosine-пространстве без отдельных профилей и калибровки. Реальное сравнение требует одинакового русского корпуса, одинаковых окон, FAR/FRR/open-set метрик и измерения RAM/RTF на телефоне.

