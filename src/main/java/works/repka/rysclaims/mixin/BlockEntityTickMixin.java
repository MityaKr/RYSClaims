package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Machines;

/**
 * Место машины для фейкового игрока (разбор фейков 20.11): на время тика блок-сущности на сервере она запоминается в
 * {@link Machines#ticking()}. Ломатель и установщик Actually Additions, механизмы Immersive Engineering, Ars Nouveau и
 * другие с общим профилем без хозяина действуют из своего тика: место их блок-сущности решает, своя это земля или нет.
 *
 * <p>Обёртка вокруг вызова тикера (как у Neruina, обёртки MixinExtras складываются цепочкой; Lithium правит другие вызовы
 * этого метода). Клиентские блок-сущности не трогаются. Прежнее значение возвращается в {@code finally}.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
abstract class BlockEntityTickMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void rysclaims$machine(BlockEntityTicker<BlockEntity> ticker, Level level, BlockPos pos, BlockState state, BlockEntity be,
                                   Operation<Void> original) {
        if (level.isClientSide) {
            original.call(ticker, level, pos, state, be);
            return;
        }
        BlockEntity prev = Machines.ticking(be);
        try {
            original.call(ticker, level, pos, state, be);
        } finally {
            Machines.ticking(prev);
        }
    }
}
