package works.repka.rysclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.server.Machines;
import works.repka.rysclaims.server.Protection;

/**
 * Снаряд по блоку (план, раздел 11, строка 10): горящая стрела и ТНТ, костёр, хорус, горшок, сталактит, линза бура Botania
 * спрашивают {@code Projectile.mayInteract}. Хозяин игрок судится правом «Строить» в клетке, хозяин монстр переключателем
 * «Мобы портят блоки» в клетке, а не там, где стоит моб.
 */
@Mixin(Projectile.class)
abstract class ProjectileMixin {
    @Inject(method = "mayInteract", at = @At("HEAD"), cancellable = true)
    private void rysclaims$owner(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Entity owner = ((Projectile) (Object) this).getOwner();
        if (owner instanceof Player p) {
            if (!Protection.check(p, level, pos, Rights.Action.BUILD)) cir.setReturnValue(false);
        } else if (owner instanceof Enemy && !Protection.mobMayGrief(owner, level, pos)) {
            cir.setReturnValue(false);
        } else if (owner == null && !Machines.burst((Projectile) (Object) this, level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
