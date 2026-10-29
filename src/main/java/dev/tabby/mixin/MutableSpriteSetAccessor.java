package dev.tabby.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Safely checks whether a particle provider's sprites have been bound.
 */
@Mixin(targets = "net.minecraft.client.particle.ParticleEngine$MutableSpriteSet")
public interface MutableSpriteSetAccessor {
    @Accessor("sprites")
    List<TextureAtlasSprite> tabby$getSprites();
}
