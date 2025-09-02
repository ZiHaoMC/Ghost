package com.zihaomc.ghost.mixins;

// import com.zihaomc.ghost.features.bedrockminer.data.Rotation;
// import com.zihaomc.ghost.features.bedrockminer.task.TaskManager;
import net.minecraft.client.entity.EntityPlayerSP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;

@Mixin(EntityPlayerSP.class)
public abstract class MixinEntityPlayerSP {
    private static final Logger LOGGER = LogManager.getLogger();

    @Inject(method = "func_175161_p", at = @At("HEAD"))
    private void onUpdateWalkingPlayerPre(CallbackInfo ci) {
    // --- 暂时禁用破基岩功能以通过编译 ---
        /*
        Rotation rotation = TaskManager.getInstance().getRotationUtils().getRotation();
        EntityPlayerSP player = (EntityPlayerSP) (Object) this;

        // [LOGGING] 检查是否需要修改视角
        if (rotation.hasYaw() || rotation.hasPitch()) {
            float originalYaw = player.rotationYaw;
            float newYaw = rotation.getYaw(originalYaw);
            float originalPitch = player.rotationPitch;
            float newPitch = rotation.getPitch(originalPitch);
            
            LOGGER.info("[BedrockMiner-DEBUG] MixinEntityPlayerSP.onUpdateWalkingPlayerPre: Spoofing rotation! Original (Y/P): " + originalYaw + "/" + originalPitch + " -> New (Y/P): " + newYaw + "/" + newPitch);
        }
        
        try {
            if (rotation.hasYaw()) {
                setRotationField(player, new String[]{"rotationYaw", "field_70177_z"}, rotation.getYaw());
            }
            if (rotation.hasPitch()) {
                setRotationField(player, new String[]{"rotationPitch", "field_70125_A"}, rotation.getPitch());
            }
        } catch (Exception e) {
             // e.printStackTrace();
        }
        */
    }

    private void setRotationField(EntityPlayerSP player, String[] fieldNames, float value) throws NoSuchFieldException, IllegalAccessException {
        Field field = null;
        for (String name : fieldNames) {
            try {
                field = EntityPlayerSP.class.getDeclaredField(name);
                break; 
            } catch (NoSuchFieldException ignored) {
            }
        }

        if (field != null) {
            field.setAccessible(true); 
            field.setFloat(player, value); 
        } else {
            throw new NoSuchFieldException("Could not find any of the fields: " + String.join(", ", fieldNames));
        }
    }
}