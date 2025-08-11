package com.zihaomc.ghost.mixins;

import com.zihaomc.ghost.features.bedrockminer.data.Rotation;
import com.zihaomc.ghost.features.bedrockminer.task.TaskManager;
import net.minecraft.client.entity.EntityPlayerSP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(EntityPlayerSP.class)
public abstract class MixinEntityPlayerSP {

    // 我们不再使用 @Shadow，所以删除这些字段

    /**
     * **核心修正**
     * 目标方法: onUpdateWalkingPlayer()
     * 1.8.9 SRG 名称: func_175161_p
     */
    @Inject(method = "func_175161_p", at = @At("HEAD"))
    private void onUpdateWalkingPlayerPre(CallbackInfo ci) {
        Rotation rotation = TaskManager.getInstance().getRotationUtils().getRotation();
        EntityPlayerSP player = (EntityPlayerSP) (Object) this;

        try {
            if (rotation.hasYaw()) {
                // 使用反射来手动设置 rotationYaw 字段
                // "rotationYaw" 在混淆后叫 "field_70177_z"
                setRotationField(player, new String[]{"rotationYaw", "field_70177_z"}, rotation.getYaw());
            }
            if (rotation.hasPitch()) {
                // 使用反射来手动设置 rotationPitch 字段
                // "rotationPitch" 在混淆后叫 "field_70125_A"
                setRotationField(player, new String[]{"rotationPitch", "field_70125_A"}, rotation.getPitch());
            }
        } catch (Exception e) {
            // 在实际游戏中，这里最好有一个日志记录
            // e.printStackTrace();
        }
    }

    // 一个辅助方法，用于通过反射设置字段值
    private void setRotationField(EntityPlayerSP player, String[] fieldNames, float value) throws NoSuchFieldException, IllegalAccessException {
        Field field = null;
        // 依次尝试每个可能的名字 (开发名, 混淆名)
        for (String name : fieldNames) {
            try {
                field = EntityPlayerSP.class.getDeclaredField(name);
                break; // 找到了就跳出循环
            } catch (NoSuchFieldException ignored) {
                // 没找到，继续尝试下一个名字
            }
        }

        if (field != null) {
            field.setAccessible(true); // 将私有字段设为可访问
            field.setFloat(player, value); // 设置值
        } else {
            // 如果所有名字都试过了还找不到，就抛出异常
            throw new NoSuchFieldException("Could not find any of the fields: " + String.join(", ", fieldNames));
        }
    }
}