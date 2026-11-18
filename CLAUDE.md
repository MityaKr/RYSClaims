# RYSClaims

Земля команд для семьи RYS: приват, лимиты, прогрузка, защита. Заменяет FTB Chunks (вместе с RYSMap). modid `rysclaims`, пакет `works.repka.rysclaims`, NeoForge 1.21.1. Автор Дмитрий (MityaYS, RepkaWorks).

План и макеты: `../../_shared/_audit/map/plan.md` (разделы 6, 9.2, 10.5, 11, 12, этапы 13.4), факты разведки `R0-facts.md`, макеты `img/30`–`53`. Вид и тон семьи: `../RYSCore/CLAUDE.md`, раздел «Карта, приват и переезд (1.11)».

## Как работать с автором

* Общаться только по-русски. Прогресс списком, сделанное помечать ✅.
* Механики не выдумывать: CFR, исходники `../RYScope/research/mcsrc`, логи, тест в новом мире.
* Файлы не удалять, ненужное в `_stale`.
* Тексты для игроков черновые (`lang/ru_ru.json`), автор смотрит их до выпуска.

## Устройство

* `data/` без игры (кроме NBT): `ClaimData` вся земля в памяти и формат `rysclaims.dat`, `Claim`, `TeamLand` (добавки, права, переключатели, доверенные), `Rights`, `Limits`, `PlayerBonus`, `Settings` (снимок настроек), `Plots` (участки на лету), `Chunks` (упаковка как `ChunkPos.asLong`). `ClaimSavedData` это только обёртка `SavedData`.
* `server/ClaimService`: вся логика без игры (занять, освободить с отсчётом, оставить, прогрузить, права, машины, события команд). Измерение в нём строка id, время из `clock`. Клиенту не верим.
* `server/Loader` + `LoadPlan`: билеты `TicketController` `rysclaims:land` (сущность = UUID команды, ticking). Проверка при загрузке мира снимает лишние, `sync` раз в 10 секунд и по событиям сверяет поставленное с нужным.
* `server/ClaimsServer`: жизнь на сервере (ServerStarting, высокий приоритет, раньше переезда ядра в ServerStarted), тик, вход.
* `server/Commands`: `/claims`; `server/ServerConfig`: `world/serverconfig/rysclaims-server.toml`.
* `api/ClaimsApiImpl`: контракт ядра `claims.ClaimsApi`.
* `migrate/FtbWorld`, `FtbSettings`: чтение файлов FTB через `migrate.Snbt` ядра (своего читателя нет).
* Права управления землёй (`rysclaims:claim`, `force`, `rights`) регистрируются в таблице прав RYSTeams (`TeamsApi.registerPermission`), второй таблицы ролей нет.

## Принятые решения

* Режим личных добавок `sum` складывает только добавки, а не полные лимиты, как FTB; `largest`, `owner`, `average` совпадают с FTB.
* Добавка за участника считается за каждого сверх первого.
* Лидеру своей земли можно всё; права монотонны: «все» ⊇ «союзники» ⊇ «команда»; гость в 0.1 получает столбец «Союзники».
* В `rights` хранятся только четыре действия доступа; права управления живут в RYSTeams (план, решение 18).
* `/claims admin bonus <игрок>` (личная добавка) добавлен сверх списка плана: без него личную добавку не выдать без квеста.
* `/claims loadmode online|always`: выбор команды в пределах `maxForceLoadMode` сервера (в плане это окно `42`).

## Проверка

```bash
gradlew test                 # JUnit без игры: хранилище, лимиты, сервис, билеты, файлы FTB
gradlew runGameTestServer    # тестовый сервер без окна, куча 2 ГБ: команды, билеты, выход игрока
```

* Настоящие файлы FTB RepYS берутся из `../../_shared/_saved/fixtures/ftb-2026-11-18` (вне git, свойство `-Pfixtures=`); без папки тест пропускается. В git лежат заготовки того же формата `src/test/resources/ftb`.
* GameTest живут в `src/gametest` и в jar не попадают. Перед запуском игры проверить свободную память.
* Сборка требует соседний `../RYSCore` с выполненным `gradlew publish` (ядро 1.11.0).
