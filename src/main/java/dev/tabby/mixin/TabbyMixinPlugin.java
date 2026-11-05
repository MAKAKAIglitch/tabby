package dev.tabby.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies third-party compatibility mixins only when their target mod is installed.
 * Vanilla Tabby mixins remain enabled regardless of the optional mod set.
 */
public final class TabbyMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".VoxyLevelRendererMixin")) {
            return FabricLoader.getInstance().isModLoaded("voxy");
        }
        if (mixinClassName.endsWith(".MeteorTickEventBusMixin")
            || mixinClassName.endsWith(".MeteorAutoBreedMixin")) {
            return FabricLoader.getInstance().isModLoaded("meteor-client");
        }
        if (mixinClassName.endsWith(".SimpleVoiceChatClientManagerMixin")) {
            return FabricLoader.getInstance().isModLoaded("voicechat");
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, String mixinClassName) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
