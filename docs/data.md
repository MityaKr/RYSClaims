# Данные и лимиты RYSClaims

## Файл `world/data/rysclaims.dat`

`SavedData` в хранилище Верхнего мира, NBT, формат окончательный с первого выпуска (план, раздел 9.2). Читает и пишет `data.ClaimData`.

| Поле | Что |
|---|---|
| `v` | версия формата, сейчас 1; файл новее мода не читается (ошибка, а не затирание) |
| `edits` | число правок земли игроками и операторами: откат переезда отказывает, если оно выросло |
| `ids` | UUID команд по номерам; поле `team` у чанка это номер в этом списке |
| `teams.<uuid>` | `bonusClaims`, `bonusForce` (добавка команде), `lastSeen`, `loadMode` (`online`/`always`), `loadHours` (0.2), `visibility` (`all`/`allies`/`team`), `rights` (`build`, `open`, `use`, `entity`: списки столбцов), `flags` (`ownExplosions`, `mobGrief`, `pvp`, `machinesInside`, `machinesOutside`, `machinesProfile`), `trusted`, `machineNames`, `legacy {name, source}` (команда FTB без пары), `plots` (0.2) |
| `claims.<измерение>` | список `{pos, team, by?, at, plot, force, releaseAt}`; `pos` как `ChunkPos.asLong`, время в мс от эпохи |
| `players.<uuid>` | личные добавки `bonusClaims`, `bonusForce`: уходят с игроком |
| `archive` | распавшиеся команды (0.2), хранится как есть |

Команда без чанков и без правок в файл не пишется.

## Лимиты

```
лимит = общий сервера + за участника × (участников − 1) + личные добавки по режиму + добавка команде
потолок: hardClaimLimit / hardForceLimit, если не 0; меньше нуля не бывает
```

Режимы личных добавок (`personalBonusMode`): `largest` наибольшая (RepYS, как FTB), `sum` сумма добавок, `leader` только лидера (FTB `owner`), `average` средняя вниз. Освобождаемые чанки входят в лимит до конца отсчёта.

Пример RepYS 18.11: общий 25, у владельца VENIK личная добавка 100, режим `largest`: лимит прогрузки 125, клеймов 500. Проверяется тестом `FtbFilesTest` на копии файлов сервера.

## Прогрузка

Чанк прогружен, если у него включена прогрузка и команда активна: кто-то из неё в сети (или сам владелец личной земли), либо сервер разрешил `maxForceLoadMode = always` и команда выбрала `always`. Билеты `rysclaims:land` NeoForge хранит сам; при запуске проверка снимает лишние, затем `Loader.sync` ставит недостающие. Билеты FTB Chunks после его удаления NeoForge выбрасывает сам (`R0-facts.md`, раздел 4.1).

## Права

Строки `build`, `open`, `use`, `entity` на столбцы `leader`, `officer`, `member`, `guest`, `allies`, `all`. В 0.1 три столбца: команда (лидер, офицер, участник), союзники (союзные команды RYSTeams и доверенные игроки), все. Гость получает столбец «Союзники». Лидеру своей земли можно всё. Наборы: `team`, `team_and_allies`, `open`; права новых команд задаёт сервер построчно (`[newTeams]`).

Машины (`ClaimsApi.canMachine`): профиль участника с его правами (`machinesProfile`), затем место (машина на земле той же команды не дальше 2 чанков, `machinesInside`), иначе `machinesOutside`.
