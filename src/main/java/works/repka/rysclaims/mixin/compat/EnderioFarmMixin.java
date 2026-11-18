package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Machines;

/**
 * Ферма EnderIO 8.2.12 (R0, раздел 1.3): задачи сбора убирают урожай {@code setBlockAndUpdate(AIR)} без событий, дроп
 * собирается до этого. Отмена в начале {@code process(позиция, ферма)}: задача отвечает «не моя» ({@code IGNORED}), ферма
 * идёт к следующей клетке, ничего не собрав. Посадка идёт через {@code useOn} и судится событием по профилю хозяина.
 */
@Pseudo
@Mixin(targets = {
    "com.enderio.enderio.content.machines.farming_station.tasks.HarvestBlockFarmTask",
    "com.enderio.enderio.content.machines.farming_station.tasks.HarvestCropFarmTask",
    "com.enderio.enderio.content.machines.farming_station.tasks.HarvestTreeFarmTask",
    "com.enderio.enderio.content.machines.farming_station.tasks.HarvestStemCropsFarmTask",
    "com.enderio.enderio.content.machines.farming_station.tasks.HarvestNetherWartFarmTask",
    "com.enderio.enderio.content.machines.farming_station.tasks.HarvestFlower",
    "com.enderio.enderio.content.machines.farming_station.tasks.HarvestPitcherFarmTask"
}, remap = false)
abstract class EnderioFarmMixin {
    private static Object rysclaims$ignored;

    @Inject(method = "process(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)Lcom/enderio/enderio/api/farm/FarmInteraction;",
        at = @At("HEAD"), cancellable = true, require = 0)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rysclaims$harvest(BlockPos pos, BlockEntity farm, CallbackInfoReturnable cir) {
        if (Machines.blockEntity(farm, farm.getLevel(), pos)) return;
        try {
            if (rysclaims$ignored == null) {
                Class<? extends Enum> type = (Class<? extends Enum>) Class.forName("com.enderio.enderio.api.farm.FarmInteraction");
                rysclaims$ignored = Enum.valueOf(type, "IGNORED");
            }
            cir.setReturnValue(rysclaims$ignored);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // другая версия EnderIO: задачу не трогаем
        }
    }
}
