package com.zihaomc.ghost.features.visual;

import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * [最终版]
 * 使用 RenderPlayerEvent 来确保与 OptiFine 的最大兼容性。
 * 这是隐藏玩家身上箭矢的标准且最可靠的方法。
 */
public class PlayerArrowRendererHandler {

    // 用于临时存储玩家身上真实的箭矢数量
    private int originalArrowCount = -1;

    /**
     * 在渲染玩家之前触发。
     */
    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        // 如果功能未开启，直接返回
        if (!GhostConfig.hideArrowsOnPlayers) {
            return;
        }

        EntityPlayer player = event.entityPlayer;
        
        int currentArrowCount = player.getArrowCountInEntity();

        if (currentArrowCount > 0) {
            // 备份真实的箭矢数量
            this.originalArrowCount = currentArrowCount;
            // 临时将箭矢数量设置为0，以阻止渲染
            player.setArrowCountInEntity(0);
        }
    }

    /**
     * 在渲染玩家之后触发。
     */
    @SubscribeEvent
    public void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        // 如果我们之前修改过数量，就将其恢复
        if (this.originalArrowCount != -1) {
            EntityPlayer player = event.entityPlayer;
            
            // 恢复真实的箭矢数量，以保证游戏逻辑正常
            player.setArrowCountInEntity(this.originalArrowCount);
            // 重置备份变量
            this.originalArrowCount = -1;
        }
    }
}