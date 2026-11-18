package works.repka.rysclaims.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.compat.create.ContraptionPlace;
import works.repka.rysclaims.server.Machines;

/**
 * Постройка Create 6.0.10 (план, раздел 11, строки 31, 32): сборка и разборка идут {@code setBlock} без событий.
 *
 * <p>Сборка: {@code movementAllowed(состояние, уровень, позиция)} спрашивается для каждого блока в {@code moveBlock};
 * «нельзя» на блоке земли, закрытой якорю, значит: сосед не прилипает, а блок в направлении толчка роняет сборку
 * ({@code AssemblyException.unmovableBlock}, как у неподвижного блока). Разборка: {@code addBlocksToWorld} уже умеет
 * не ставить блок, если на месте твёрдость -1 (блок выпадает предметом, на месте ничего не ломается); на земле,
 * закрытой якорю, твёрдость подменяется на -1. Якорь это блок управления ({@link ContraptionPlace}), он хранится в NBT.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.Contraption", remap = false)
abstract class CreateContraptionMixin implements ContraptionPlace {
    @Unique private static final String RYSCLAIMS_KEY = "RysClaimsController";

    @Shadow public BlockPos anchor;

    @Unique private @Nullable BlockPos rysclaims$controller;

    @Override
    public @Nullable BlockPos rysclaims$place() {
        return rysclaims$controller != null ? rysclaims$controller : anchor;
    }

    @Override
    public void rysclaims$controller(BlockPos pos) {
        rysclaims$controller = pos.immutable();
    }

    @Inject(method = "movementAllowed", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$allowed(BlockState state, Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!Machines.contraptionAt(level, this, pos, "create:contraption")) cir.setReturnValue(false);
    }

    @WrapOperation(method = "addBlocksToWorld", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroySpeed(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
    private float rysclaims$disassemble(BlockState state, BlockGetter getter, BlockPos pos, Operation<Float> original) {
        if (getter instanceof Level level && !Machines.contraptionAt(level, this, pos, "create:contraption")) return -1f;
        return original.call(state, getter, pos);
    }

    @Inject(method = "writeNBT", at = @At("RETURN"), require = 0)
    private void rysclaims$write(HolderLookup.Provider registries, boolean spawnPacket, CallbackInfoReturnable<CompoundTag> cir) {
        if (rysclaims$controller != null && cir.getReturnValue() != null) {
            cir.getReturnValue().put(RYSCLAIMS_KEY, NbtUtils.writeBlockPos(rysclaims$controller));
        }
    }

    @Inject(method = "readNBT", at = @At("RETURN"), require = 0)
    private void rysclaims$read(Level level, CompoundTag tag, boolean spawnData, CallbackInfo ci) {
        rysclaims$controller = NbtUtils.readBlockPos(tag, RYSCLAIMS_KEY).orElse(null);
    }
}
