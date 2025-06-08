package com.zihaomc.ghost.features.playeresp;

import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class PlayerESPHandler {

    private final Minecraft mc = Minecraft.getMinecraft();

    @SubscribeEvent
    public void onRenderPlayerPre(RenderLivingEvent.Pre<EntityPlayer> event) {
        if (!GhostConfig.enablePlayerESP || !(event.entity instanceof EntityPlayer) || event.entity == mc.thePlayer) {
            return;
        }

        GlStateManager.pushMatrix();

        // 核心：使用多边形偏移来解决深度冲突 (Z-Fighting)
        // 开启这个功能
        GlStateManager.enablePolygonOffset();
        // 设置偏移量。-1.0, -1000000.0 是常用值，将ESP的渲染“拉”到更靠近摄像机的位置，
        // 使其在深度测试中优先通过，从而覆盖掉墙壁等物体。
        // factor: -1.0, units: -1000000.0
        GlStateManager.doPolygonOffset(-1.0F, -1000000.0F);

        // 我们不禁用深度测试，让它正常工作，只是通过偏移来“欺骗”它
        // 这样可以保留正确的模型内部遮挡关系
    }

    @SubscribeEvent
    public void onRenderPlayerPost(RenderLivingEvent.Post<EntityPlayer> event) {
        if (!GhostConfig.enablePlayerESP || !(event.entity instanceof EntityPlayer) || event.entity == mc.thePlayer) {
            return;
        }
        
        // 恢复多边形偏移
        // factor: 1.0, units: 1000000.0 (恢复到正值或0,0)
        GlStateManager.doPolygonOffset(1.0F, 1000000.0F);
        GlStateManager.disablePolygonOffset();

        GlStateManager.popMatrix();
    }
}