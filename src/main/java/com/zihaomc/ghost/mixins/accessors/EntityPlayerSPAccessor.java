package com.zihaomc.ghost.mixins.accessors;

import net.minecraft.client.entity.EntityPlayerSP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityPlayerSP.class)
public interface EntityPlayerSPAccessor {
    /**
     * 获取上一次报告给服务器的 Yaw。
     * 移除括号里的 SRG 名称 ("field_175164_e")。
     */
    @Accessor
    float getLastReportedYaw();

    /**
     * 获取上一次报告给服务器的 Pitch。
     * 移除括号里的 SRG 名称 ("field_175162_f")。
     */
    @Accessor
    float getLastReportedPitch();
    
    /**
     * 调用 sendHorseJump 方法。
     * 对于 @Invoker，我们仍然需要提供名称，因为它无法从方法名推断。
     * 这个错误很可能是连锁反应，我们先试试只改 Accessor。如果还报错，再回来处理这个。
     */
    @Invoker("sendHorseJump")
    void invokeSendHorseJump();
}