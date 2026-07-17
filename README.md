# TinyCraftLauncher

Отдельный Windows-лаунчер для игры [TinyCraft](https://github.com/vakisnn-gd/TinyCraft). Код игры и её релизы находятся в репозитории TinyCraft.

## Быстрый старт

```powershell
cd TinyCraftLauncher
javac -encoding UTF-8 --release 8 -d out Launcher.java
java -cp "out" Launcher
```

## Сборка релиза

```powershell
.\build-launcher.bat
```

После сборки появится отдельное приложение в `TinyCraftLauncher\app\TinyCraftLauncher\TinyCraftLauncher.exe` и ZIP в `TinyCraftLauncher\release\TinyCraftLauncher-windows.zip`.

## Что здесь лежит

- `Launcher.java` - launcher UI и логика установки/запуска.
- `build-launcher.bat` - сборка Windows app image и ZIP.
- `TinyCraftLauncher.ico` - иконка для launcher-сборки.
- `app\TinyCraftLauncher\TinyCraftLauncher.exe` - готовое приложение после сборки.
