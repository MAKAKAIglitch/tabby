package dev.tabby.mixin;

import dev.tabby.tab.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Prevents inactive tabs and not-yet-bound particle sprite sets from crashing
 * or mutating Minecraft's shared particle engine.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Shadow
    protected ClientLevel level;

    @Shadow
    @Final
    private Map<ResourceLocation, ?> spriteSets;

    private boolean tabby$shouldSuppressParticleMutation() {
        Minecraft minecraft = Minecraft.getInstance();
        return TabManager.isBackgroundContext()
            || this.level == null
            || !minecraft.isGameLoadFinished()
            || minecraft.getOverlay() != null;
    }

    @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
    private void tabby$keepActiveParticleLevel(ClientLevel level, CallbackInfo ci) {
        if (TabManager.isBackgroundContext()) {
            ci.cancel();
        }
    }

    @Inject(
        method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$dropUnsafeParticle(
        ParticleOptions options,
        double x,
        double y,
        double z,
        double xa,
        double ya,
        double za,
        CallbackInfoReturnable<Particle> cir
    ) {
        if (this.tabby$shouldSuppressParticleMutation()) {
            cir.setReturnValue(null);
            return;
        }

        ResourceLocation particleId = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        Object spriteSet = this.spriteSets.get(particleId);
        if (spriteSet != null) {
            List<TextureAtlasSprite> sprites =
                ((MutableSpriteSetAccessor) spriteSet).tabby$getSprites();
            if (sprites == null || sprites.isEmpty()) {
                // Some providers dereference their sprite set in the particle constructor.
                // Drop that particle until its atlas entry has been bound by resource reload.
                cir.setReturnValue(null);
            }
        }
    }

    @Inject(
        method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void tabby$dropBackgroundEmitter(Entity entity, ParticleOptions options, CallbackInfo ci) {
        if (this.tabby$shouldSuppressParticleMutation()) {
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
        if (this.tabby$shouldSuppressParticleMutation()) {
            ci.cancel();
        }
    }

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void tabby$dropBackgroundAddedParticle(Particle particle, CallbackInfo ci) {
        if (this.tabby$shouldSuppressParticleMutation()) {
            ci.cancel();
        }
    }
}
