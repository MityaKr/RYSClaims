# Данные и лимиты RYSClaims

## Файл `world/data/rysclaims.dat`

`SavedData` в хранилище Верхнего мира, NBT, формат окончательный с первого выпуска (план, раздел 9.2). Читает и пишет `data.ClaimData`.

| Поле | Что |
|---|---|
| `v` | версия формата, сейчас 1; файл новее мода не читается (ошибка, а не затирание) |
| `edits` | число правок земли игроками и операторами: откат переезда отказывает, если оно выросло |
| `ids` | UUID команд по номерам; поле `team` у чанка это номер в этом списке |
| `teams.<uuid>` | `bonusClaims`, `bonusForce` (добавка команде), `lastSeen`, `loadMode` (`online`/`always`), `loadHours` (0.2), `visibility` (`all`/`allies`/`team`), `rights` (`build`, `open`, `use`, `entity`: списки столбцов), `flags` (`ownExplosions`, `mobGrief`, `pvp`, `machinesInside`, `machinesOutside`, `machinesProfile`), `trusted`, `machineNames`, `legacy {name, source, members, owner}` (команда FTB без пары: её участники остаются своими), `plots` (0.2) |
| `claims.<измерение>` | список `{pos, team, by?, at, plot, force, releaseAt}`; `pos` как `ChunkPos.asLong`, время в мс от эпохи |
| `homes` | `[{sub, team}]`: дом летающей постройки Sable, UUID подуровня → команда земли, где её собрали (0.0.6) |
| `players.<uuid>` | личные добавки `bonusClaims`, `bonusForce`: уходят с игроком |
| `archive` | распавшиеся команды (0.2), хранится как есть |

Команда без чанков и без правок в файл не пишется.

## Журнал отказов `world/rysclaims/log/<команда>.jsonl`

Строка JSON на запись: `t` последний раз, `f` первый, `n` сколько раз, `a` UUID игрока или профиля машины, `name`, `m` машина, `r` причина (`build`, `open`, `use`, `entity`, `fluid`, `pvp`, `guest`, `wilderness`, `machine`), `d` измерение, `x`, `y`, `z`. Повторы того же действия в том же чанке склеиваются в памяти и уходят в файл раз в минуту; строки старше `logDays` убираются раз в сутки. Файл земли журнал не трогает.

## Через границу земли

| Что | Правило | Настройка `[borders]` |
|---|---|---|
| вода и лава | на землю другой команды (и со свободной на землю команды) не текут; по своей земле и на ничью текут | `fluidsAcrossBorders` (false) |
| огонь, молния | так же, у «Громовержца» ещё право «Строить» его игрока | `fireAcrossBorders` (false) |
| поршень | не двигает блоки через границу | `pistonsAcrossBorders` (false) |
| взрыв без хозяина, снаряд CBC | `own`: только свой на своей земле с переключателем; `never`; `always`. Снаряд CBC центра не знает: ломает землю команды только при `always` | `ownerlessExplosions` (own) |

## Машины мимо событий

| Путь | Как ловим | Место | Профиль |
|---|---|---|---|
| фейковый игрок через `ServerLevel.mayInteract` (AE2 разрушение, Building Gadgets 2, насос Sophisticated) | `ServerLevelMixin` | не верим | UUID и список имён |
| фейковый игрок через `BlockItem.placeBlock` (AE2 формирование, Integrated Tunnels) | `BlockItemMixin` | не верим | UUID и список имён |
| Oritech, Cyclic, IE экскаватор, RFTools Builder, ферма EnderIO | `mixin/compat`, в начале метода работы | блок машины, дальность любая (IE 2 чанка) | нет |
| духи Occultism | `mixin/compat` | сам дух, 2 чанка | хозяин духа |
| AE2 пространственный ввод-вывод | вся область | нет | хозяин сети |
| реактор Draconic | каждый убираемый блок | центр взрыва | правило взрыва без хозяина |

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

## Переезд с FTB Chunks (шаг `claims`)

| FTB | RYSClaims |
|---|---|
| чанк команды, `time`, `force_loaded` | чанк той же команды, время занятия, прогрузка включена |
| команда с парой в RYSTeams (тот же UUID) | та же команда |
| все игроки команды уже в RYSTeams | команда RYSTeams владельца |
| команда без пары, серверная команда | старый UUID, `legacy`; участники остаются своими |
| личная команда игрока | личная земля |
| `extra_claim_chunks`, `extra_force_load_chunks` личного файла | личная добавка игрока |
| `max_claim_chunks`, `max_force_load_chunks` файла команды | наш лимит не меньше; разница добавкой команде |
| `block_edit_mode`, `block_interact_mode`, `entity_interact_mode` + `nonliving_entity_attack_mode` | строки «строить», «открывать» и «двери», «животные» (по более строгому) |
| `allow_explosions`, `allow_mob_griefing`, `allow_pvp`, `allow_fake_players_by_id`, `allow_fake_players` | `ownExplosions`, `mobGrief`, `pvp`, `machinesProfile`, `machinesOutside`; `machinesInside` включён |
| `allow_named_fake_players`, `claim_visibility` | `machineNames`, `visibility` |
| ранг `ally` | доверенный игрок |
| `ftbchunks-world.snbt` | `rysclaims-server.toml`, если его ещё не трогали |

Не переносятся: `expiry_time` (срок задаёт режим сервера), `prevented_access` (журнал в 0.2), `original_claims` (поле `by` в 0.2). Всё это видно строками отчёта.
