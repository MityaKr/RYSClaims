# RYSClaims и FTB Chunks 2101.1.22: сверка перед бетой

Критерий выпуска беты (план, раздел 13.6, пункт 3): каждая строка таблицы «Не слабее FTB Chunks» закрыта и с тестом, у каждой строки «Известно не закрыто» записано решение. Сверено по коду 0.0.45 (19.11.2026).

Тесты: JUnit (`gradlew test`, 0.0.45: все зелёные) и GameTest (`gradlew runGameTestServer`). GameTest последний раз гонялся целиком на 0.0.17 (21 из 21); тесты, добавленные позже (`hopperAcrossBorder` в новом виде, `dispenserAcrossBorder`, `ownerlessProjectile`), написаны и компилируются, но не запускались: на машине шли сервер и игра автора, ночью 19.11 тяжёлые запуски держит другая сессия. Это первая строка в `_shared/_audit/UNTESTED.md`.

## Не слабее FTB Chunks

| Защита FTB Chunks | Чем закрыта у нас | Тест | Итог |
|---|---|---|---|
| `EDIT_BLOCK`: ломать, ставить, ЛКМ | `Protection.onBreak`, `onPlace` (и мультиблоки), `onLeftClick`; гашение на клиенте `InputGate` | GameTest `breakAndOpen` | закрыто |
| `INTERACT_BLOCK`: любой правый клик, кроме тега | `Protection.onRightClickBlock`: по умолчанию «Открывать», тег `rysclaims:use` это «Двери», `rysclaims:free` всегда | `breakAndOpen` | закрыто; с едой, луком, щитом в руке отказ молчит, предмет применяется (0.0.30) |
| `RIGHT_CLICK_ITEM`: предметы из чёрного списка | `onRightClickItem` + тег `rysclaims:build_items`, ведро и лодка по лучу в двух позициях | `breakAndOpen` | закрыто |
| `EDIT_FLUID`: ведро | `onRightClickBlock` (обе позиции), `onRightClickItem` | `breakAndOpen` | закрыто |
| `INTERACT_ENTITY` | `EntityInteract`, `EntityInteractSpecific` → `entityAllowed` | `fakePlayerProfile`, JUnit `ClaimServiceTest` | закрыто |
| `ATTACK_NONLIVING_ENTITY` | `AttackEntityEvent` → `entityAllowed` (рамки, стойки, лодки, вагонетки) | JUnit | закрыто |
| `FARMLAND_TRAMPLE` | `onTrample` (игрок правом «Строить», мобы переключателем) | JUnit | закрыто |
| `LIVING_HURT`: бой | `onDamage`, `pvpAllowed` (режим сервера и переключатель команды), снаряды в `onProjectile` | JUnit `ClaimServiceTest` | закрыто |
| `ExplosionEvent.DETONATE`, `protect_unknown_explosions` | `onDetonate`: хозяин игрок правом, хозяин моб переключателем, без хозяина `ownerlessExplosions`; ещё сущности | `explosionWithoutOwner`, JUnit `BordersTest` | закрыто, сильнее FTB |
| `EntityMobGriefingEvent` по тегу | `onMobGrief` (только монстры), эндермен `EndermanTakeBlockGoalMixin` с позицией | `lightningAndEnderman` | закрыто |
| `FluidPlaceBlockEvent` | `onFluidPlace` | `waterAcrossBorder` | закрыто |
| `PistonBaseBlockMixin` | `onPiston`: толкание и втягивание через границу, падающие блоки `FallingBlockMixin` | `pistonAcrossBorder`, `fallingAcrossBorder` | закрыто, сильнее FTB |
| `FlowingFluidMixin` | `FlowingFluidMixin` → `Borders.fluid` (в RepYS открыто, как было в FTB: `fluidsAcrossBorders = true` после переноса) | `waterAcrossBorder` | закрыто |
| `FireBlockMixin` | `FireBlockMixin`, молния `LightningBoltMixin` | `fireAcrossBorder`, `lightningAndEnderman` | закрыто |
| `ArmorStandMixin`, `ItemFrameMixin`, `VehicleEntityMixin` | `entityAllowed`, снаряды по ним `onProjectile`, стрела раздатчика (0.0.29) | `projectileOwner`, `ownerlessProjectile` | закрыто |
| фейковые игроки: все, по id, по имени | `Protection.machine`: имена команды, профиль, «Машины снаружи»; ещё «по месту» для машин с блок-сущностью (13 связок) | `fakePlayerProfile`, `mayInteractForMachines`, `placeBlockForMachines`, `blockEntityPlace`, `cyclicBreaker`, `createDrill` | закрыто, сильнее FTB |
| `claim_visibility` | «Землю видят», скрытая земля без имени и цвета | JUnit `SyncTest` | закрыто |
| `no_wilderness` | `noWilderness`, `noWildernessDimensions` | JUnit | закрыто |
| простой команды: `max_idle_days_before_unclaim`, `…_unforce` | `idleDaysBeforeUnclaim`, `idleDaysBeforeUnforce`, предупреждение в ленту за неделю, перенос значений и последнего входа из FTB (0.0.40) | JUnit `idleTeamLosesLand` | закрыто, сильнее FTB |

Сверх FTB: воронки, раздатчики, выбрасыватели, плиты, растяжки, кнопки, скалк, рост деревьев через границу (`CrossGameTests`), Jade (0.0.19), разборка построек Create и Sable (0.0.20), снаряды раздатчика (0.0.29), буфер между землями (0.0.41), передача и аренда участка (0.0.42), свои права участка (0.0.43).

## Известно не закрыто: решения

| Мод | Что | Решение |
|---|---|---|
| Create Big Cannons, ракеты Aeronautics | снаряды ломают блоки своим кодом | закрыто: своё событие CBC `ProjectileDamageEvent` судится как взрыв без хозяина (`Protection.cannons`) |
| AE2, пространственный ввод-вывод | куб блоков без событий | закрыто: `Ae2SpatialMixin` (вся область), место порта `Ae2SpatialPortMixin` (0.0.22) |
| Draconic Evolution, взрыв реактора | воздух напрямую | закрыто: `DraconicReactorMixin` по правилу взрыва без хозяина |
| Mekanism, цифровой шахтёр и лазер | копают в радиусе | закрыто без перехвата: Mekanism сам зовёт `BreakEvent` с фейковым игроком хозяина (R0, раздел 1.1) |
| Create: руки, воронки, умный наблюдатель, упаковщик | берут предметы через capabilities | закрыто: `CreateArmMixin`, `CreateArmPointMixin`, `CreateInventoryMixin` по месту блок-сущности. Желоба вертикальные и границу чанка не пересекают |
| Трубы других модов (Mekanism, Pipez, Thermal и прочие) | берут из соседнего блока через capabilities | не закрываем: кто запрашивает, NeoForge не сообщает, а трубу на чужой земле не поставить. Совет в справке: сундук у самой границы не ставить |
| Waystones, `/tpa`, `/back` FTB Essentials, телепорт карты | приводят на чужую землю | не запрещаем: на чужой земле защита та же, сказано в справке |
| Power Loader, Chunky | прогрузка мимо лимитов | не ограничиваем, видно оператору |
| FTB Ultimine | листва после бревна ломается без события (R0) | не закрываем: несколько листьев у границы, строка в справке |
| Лоза (`VineBlock`) | растёт через границу без события | не закрываем: признано в `Growth`, вреда нет |
| Крафтер 1.21 | кладёт в контейнер через границу | закрыто в 0.0.46: `CrafterMixin`, правило выбрасывателя (контейнер на земле другой команды не виден, предмет выпадает) |
| Трава | растёт через границу | не закрываем: мох закрыт от костной муки, трава не портит постройки |
