# Исходный код Android

Платформа: `mobile/android` (Android-проект находится в корне репозитория).

Сборка:

```powershell
./gradlew.bat :core:test :app:assembleGigaam50Debug --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

Инструкция для пользователя — в [INSTALL.md](../INSTALL.md). Для сдачи используйте APK из GitHub Release, поскольку модели встроены в бинарный файл.
