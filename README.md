# MovieRenamer

Личный CLI-инструмент для безопасного переименования видеофайлов на Kotlin/JVM.

## Каталоги фильмов

Для русских и оригинальных названий, жанров, актёров и рейтинга нужны бесплатные
токены. Можно подключить один сервис или оба: недостающие поля добираются
из второго, а если и там пусто — поле остаётся пустым.

Оба ключа лежат в одном файле
`start/src/main/kotlin/movierenamer/catalog-tokens.properties`.
Вставьте токены вместо слова `плейсхолдер`. Реальный токен не пушить.
Как получить каждый ключ — в комментариях этого файла.

Фильмы получают имя вида:

```text
Original Title — Русское название (2021) [Жанр 1, Жанр 2] [Актёр 1, Актёр 2, Актёр 3] (6.4 TMDB) 1080p.mkv
```

Источник оценки берётся из того сервиса, откуда пришёл рейтинг: `(6.4 TMDB)`
или `(8.5 КП)`. Для российских фильмов выводится одно название. Имена серий
остаются короткими:

```text
Название S02E03 Название серии 1080p.mkv
```

Альтернатива файлу — переменные окружения, они имеют приоритет.

PowerShell:

```powershell
$env:TMDB_API_TOKEN="ваш-токен-tmdb"
$env:POISKKINO_API_TOKEN="ваш-токен-poiskkino"
.\gradlew.bat run
```

macOS/Linux:

```bash
export TMDB_API_TOKEN="ваш-токен-tmdb"
export POISKKINO_API_TOKEN="ваш-токен-poiskkino"
./gradlew run
```

Вместо переменных окружения можно передать JVM properties
`-Dtmdb.api.token=...` и `-Dpoiskkino.api.token=...`.

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Запуск

```bash
./gradlew test
./gradlew run
```

По умолчанию включён безопасный режим `DEBUG`: читается `debug/samples`, копии
создаются в `debug/results`.
