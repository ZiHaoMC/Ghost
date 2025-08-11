package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * 处理 Ghost Mod 的所有按键绑定。
 * 负责注册按键并在按下时执行相应操作。
 */
public class KeybindHandler {

    // 为需要按键切换的功能定义 KeyBinding 对象
    public static KeyBinding toggleAutoSneak;
    public static KeyBinding togglePlayerESP;
    public static KeyBinding toggleBedrockMiner;

    /**
     * 注册所有的按键绑定。
     * 此方法应在 ClientProxy 的 init 阶段被调用。
     */
    public static void registerKeybinds() {
        String category = "key.ghost.category";

        toggleAutoSneak = new KeyBinding("key.ghost.toggleAutoSneak", Keyboard.KEY_NONE, category);
        togglePlayerESP = new KeyBinding("key.ghost.togglePlayerESP", Keyboard.KEY_NONE, category);
        toggleBedrockMiner = new KeyBinding("key.ghost.toggleBedrockMiner", Keyboard.KEY_NONE, category);

        ClientRegistry.registerKeyBinding(toggleAutoSneak);
        ClientRegistry.registerKeyBinding(togglePlayerESP);
        ClientRegistry.registerKeyBinding(toggleBedrockMiner);
    }

    /**
     * 监听客户端 Tick 事件以检查按键是否被按下。
     * @param event Tick 事件对象
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getMinecraft().thePlayer == null) {
            return;
        }

        // --- 修正部分：在调用 isPressed() 前添加 null 检查 ---
        if (toggleAutoSneak != null && toggleAutoSneak.isPressed()) {
            boolean newState = !GhostConfig.enableAutoSneakAtEdge;
            GhostConfig.setEnableAutoSneakAtEdge(newState);
            sendToggleMessage("自动边缘下蹲", newState);
        }

        if (togglePlayerESP != null && togglePlayerESP.isPressed()) {
            boolean newState = !GhostConfig.enablePlayerESP;
            GhostConfig.setEnablePlayerESP(newState);
            sendToggleMessage("玩家透视 (ESP)", newState);
        }

        if (toggleBedrockMiner != null && toggleBedrockMiner.isPressed()) {
            boolean newState = !GhostConfig.enableBedrockMiner;
            GhostConfig.setEnableBedrockMiner(newState);
            sendToggleMessage("破基岩模式", newState);
        }
        // --- 修正结束 ---
    }

    /**
     * 向玩家发送功能切换状态的聊天消息。
     * @param featureName 功能的名称
     * @param enabled 功能的新状态 (true 为启用, false 为禁用)
     */
    private void sendToggleMessage(String featureName, boolean enabled) {
        EnumChatFormatting statusColor = enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED;
        String statusText = enabled ? "启用" : "禁用";

        ChatComponentText message = new ChatComponentText("");

        ChatComponentText prefix = new ChatComponentText("[Ghost] ");
        prefix.getChatStyle().setColor(EnumChatFormatting.AQUA);

        ChatComponentText feature = new ChatComponentText(featureName + " 已 ");
        feature.getChatStyle().setColor(EnumChatFormatting.GRAY);

        ChatComponentText status = new ChatComponentText(statusText);
        status.getChatStyle().setColor(statusColor);

        message.appendSibling(prefix);
        message.appendSibling(feature);
        message.appendSibling(status);

        Minecraft.getMinecraft().thePlayer.addChatMessage(message);
    }
}