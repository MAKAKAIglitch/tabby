package dev.matvey.tabby.mixin;

import dev.matvey.tabby.tab.TabManager;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PacketUtils.class)
public abstract class PacketUtilsMixin {
    @ModifyArg(
        method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/thread/BlockableEventLoop;execute(Ljava/lang/Runnable;)V"
        ),
        index = 0
    )
    private static Runnable tabby$wrapPacketTask(
        Runnable task,
        Packet<?> packet,
        PacketListener listener,
        BlockableEventLoop<?> executor
    ) {
        return TabManager.wrapPacketTask(listener, task);
    }
}
