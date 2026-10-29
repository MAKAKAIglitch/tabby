package dev.matvey.tabby.mixin;

import dev.matvey.tabby.tab.TabManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents an inactive network tab from mutating the shared particle engine.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Shadow
    protected ClientLevel level;

    @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
    private void tabby$keepActiveParticleLevel(ClientLevel level, CallbackInfo ci) {
        if (TabManager.isBackgroundContext() || this.level == null) {
            ci.cancel();
        }
    }

    @Inject(
        method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$dropBackgroundParticle(
        ParticleOptions options,
        double x,
        double y,
        double z,
        double xa,
        double ya,
        double za,
        CallbackInfoReturnable<Particle> cir
    ) {
        if (TabManager.isBackgroundContext() || this.level == null) {
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$dropBackgroundEmitter(Entity entity, ParticleOptions options, CallbackInfo ci) {
        if (TabManager.isBackgroundContext()) {
            ci.cancel();
        }
    }

    @Inject(
        method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$dropBackgroundTimedEmitter(
        Entity entity,
        ParticleOptions options,
        int lifetime,
        CallbackInfo ci
    ) {
        if (TabManager.isBackgroundContext()) {
            ci.cancel();
        }
    }

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void tabby$dropBackgroundAddedParticle(Particle particle, CallbackInfo ci) {
        if (TabManager.isBackgroundContext()) {
            ci.cancel();
        }
    }
}
