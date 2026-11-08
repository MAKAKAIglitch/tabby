package dev.tabby.mixin;

import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Allows Tabby to wrap the vanilla pause-menu disconnect callback.
 */
@Mixin(Button.class)
public interface ButtonAccessor {
    @Accessor("onPress")
    Button.OnPress tabby$getOnPress();

    @Mutable
    @Accessor("onPress")
    void tabby$setOnPress(Button.OnPress onPress);
}
