package com.zihaomc.ghost.mixins;

import com.zihaomc.ghost.features.bedrockminer.utils.BlinkUtils;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetworkManager.class)
public class MixinNetworkManager {

    /**
     * 注入到 sendPacket 方法，用于实现 Blink 功能 (暂存数据包)。
     * 目标方法: sendPacket(Packet packetIn)
     * 1.8.9 SRG 名称: func_179290_a
     * remap = false: 告诉 Mixin 不要尝试重映射这个方法名。
     */
    @Inject(method = "func_179290_a", at = @At("HEAD"), cancellable = true, remap = false)
    private void onSendPacket(Packet packetIn, CallbackInfo ci) {
        
        NetworkManager thisManager = (NetworkManager) (Object) this;

        if (BlinkUtils.blinkingConnection.get() == thisManager) {
            // TODO: 在未来的步骤中，将 packetIn 添加到 BlinkUtils 的队列中
            ci.cancel(); 
        }
    }
}