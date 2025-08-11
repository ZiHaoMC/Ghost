package com.zihaomc.ghost.features.bedrockminer;

import com.zihaomc.ghost.features.bedrockminer.task.TaskManager;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;


public class BedrockMinerHandler {
    private final Minecraft mc = Minecraft.getMinecraft();
    private final TaskManager taskManager = TaskManager.getInstance();

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null) {
            return;
        }
        // 每 tick 都让 TaskManager 工作
        taskManager.tick();
    }

    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Forge 的事件已经包含了我们需要的所有信息
        if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && mc.thePlayer.getHeldItem() == null) {
            // 空手右键切换
            if (taskManager.handleUseOnBlock(event.pos)) {
                event.setCanceled(true);
            }
        }
    }
}