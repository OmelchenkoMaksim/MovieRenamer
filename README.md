# MovieRenamer

Личный CLI-инструмент для безопасного переименования видеофайлов на Kotlin/JVM.

## TMDB

Для русских и оригинальных названий, жанров, актёров и рейтинга нужен бесплатный
TMDB API Read Access Token:

1. Создайте аккаунт на <https://www.themoviedb.org/>.
2. Получите **API Read Access Token** в настройках API.
3. Перед запуском задайте переменную окружения.

PowerShell:

```powershell
$env:TMDB_API_TOKEN="ваш-токен"
.\gradlew.bat run
```

macOS/Linux:

```bash
export TMDB_API_TOKEN="ваш-токен"
./gradlew run
```

Токен не хранится в проекте и не должен попадать в Git. Вместо переменной
окружения можно передать JVM property `-Dtmdb.api.token=...`.

Фильмы получают имя вида:

```text
Original Title — Русское название (2021) [Жанр 1, Жанр 2] [Актёр 1, Актёр 2, Актёр 3] [TMDB 8.1] 1080p.mkv
```

Для российских фильмов выводится одно название. Имена серий остаются короткими:

```text
Название S02E03 Название серии 1080p.mkv
```

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Запуск

```bash
./gradlew test
./gradlew run
```

По умолчанию включён безопасный режим `DEBUG`: читается `debug/samples`, копии
создаются в `debug/results`.
