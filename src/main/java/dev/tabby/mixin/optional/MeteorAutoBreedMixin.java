package dev.tabby.mixin.optional;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional guard for Meteor's AutoBreed tick handler.
 *
 * Some Meteor builds access ClientLevel without checking whether a world is open.
 * Tabby can legitimately display a title-screen tab while another tab owns a world,
 * so skip AutoBreed's world-only tick when Minecraft has no active level.
 */
@Pseudo
@Mixin(targets = "meteordevelopment.meteorclient.systems.modules.world.AutoBreed", remap = false)
public abstract class MeteorAutoBreedMixin {
    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void tabby$skipAutoBreedWithoutWorld(CallbackInfo ci) {
        if (Minecraft.getInstance().level == null) {
            ci.cancel();
        }
    }
}
