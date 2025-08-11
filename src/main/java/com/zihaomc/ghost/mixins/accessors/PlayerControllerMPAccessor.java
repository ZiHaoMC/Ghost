package com.zihaomc.ghost.mixins.accessors;

import net.minecraft.client.multiplayer.PlayerControllerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerControllerMP.class)
public interface PlayerControllerMPAccessor {
    @Accessor
    float getCurBlockDamageMP();

    @Accessor
    void setBlockHitDelay(int delay);

    /**
     * **核心修正**
     * 使用开发环境的方法名 (syncCurrentPlayItem)，而不是 SRG 名称 (func_78750_j)。
     * Mixin 处理器会根据这个名字进行推断。
     */
    @Invoker("syncCurrentPlayItem")
    void invokeSyncCurrentPlayItem();
}