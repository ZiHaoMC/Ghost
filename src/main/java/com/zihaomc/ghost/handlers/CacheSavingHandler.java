package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.utils.LogUtil; // <--- 导入
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 一个专门的事件处理器，用于在正确的时机保存持久化缓存。
 */
public class CacheSavingHandler {

    /**
     * 当一个世界被卸载时（例如退出单人游戏或从服务器断开），触发此事件。
     * 这是保存客户端缓存的理想时机。
     * @param event 世界卸载事件
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        // 确保我们只在客户端世界卸载时执行操作
        if (event.world.isRemote) {
            LogUtil.info("log.info.cache.saving");
            // 调用 ItemTooltipTranslationHandler 中的静态保存方法
            ItemTooltipTranslationHandler.saveCacheToFile();
        }
    }
}