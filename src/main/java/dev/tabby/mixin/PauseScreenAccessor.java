package dev.tabby.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PauseScreen.class)
public interface PauseScreenAccessor {
    @Accessor("disconnectButton")
    Button tabby$getDisconnectButton();
}
