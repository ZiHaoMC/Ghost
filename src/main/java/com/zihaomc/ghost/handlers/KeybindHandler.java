package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.NiuTransUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
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
    public static KeyBinding translateItemKey;

    /**
     * 注册所有的按键绑定。
     * 此方法应在 ClientProxy 的 init 阶段被调用。
     */
    public static void registerKeybinds() {
        String category = "key.ghost.category";

        toggleAutoSneak = new KeyBinding("key.ghost.toggleAutoSneak", Keyboard.KEY_NONE, category);
        togglePlayerESP = new KeyBinding("key.ghost.togglePlayerESP", Keyboard.KEY_NONE, category);
        toggleBedrockMiner = new KeyBinding("key.ghost.toggleBedrockMiner", Keyboard.KEY_NONE, category);
        translateItemKey = new KeyBinding("key.ghost.translateItem", Keyboard.KEY_T, category);

        ClientRegistry.registerKeyBinding(toggleAutoSneak);
        ClientRegistry.registerKeyBinding(togglePlayerESP);
        ClientRegistry.registerKeyBinding(toggleBedrockMiner);
        ClientRegistry.registerKeyBinding(translateItemKey);
    }

    /**
     * 监听客户端 Tick 事件以检查按键是否被按下。
     * 这个事件只适用于非GUI界面的按键绑定。
     * @param event Tick 事件对象
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getMinecraft().thePlayer == null) {
            return;
        }

        // --- 功能开关按键 ---
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
    }

    /**
     * 新增：监听GUI界面的键盘输入事件。
     * 这是处理在物品栏等界面中按键的正确方式。
     */
    @SubscribeEvent
    public void onGuiKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        // 检查是否在带有物品栏的GUI中 (例如，背包、箱子等)
        if (event.gui instanceof GuiContainer) {
            // 直接检查原始按键状态，而不是用 isPressed()
            // Keyboard.getEventKeyState()确保是“按下”事件，而不是“弹起”
            if (translateItemKey != null && Keyboard.getEventKeyState() && Keyboard.getEventKey() == translateItemKey.getKeyCode()) {
                handleItemTranslationKeyPress();
            }
        }
    }

    /**
     * 处理物品翻译快捷键的按下事件。
     */
    private void handleItemTranslationKeyPress() {
        if (!GhostConfig.enableItemTranslation) {
            return;
        }
        
        String itemToTranslate = ItemTooltipTranslationHandler.lastHoveredItemName;

        if (itemToTranslate == null || itemToTranslate.trim().isEmpty()) {
            return;
        }
        
        if (ItemTooltipTranslationHandler.translationCache.containsKey(itemToTranslate) ||
            ItemTooltipTranslationHandler.pendingTranslations.contains(itemToTranslate)) {
            return;
        }
        
        ItemTooltipTranslationHandler.pendingTranslations.add(itemToTranslate);
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "已发送翻译请求: " + itemToTranslate));

        new Thread(() -> {
            try {
                String result = NiuTransUtil.translate(itemToTranslate);

                // NiuTransUtil 在失败时会返回一个以 "§c" 开头的错误信息。
                // 如果是网络问题等导致结果为null或空，我们也自己构造一个错误信息。
                if (result == null || result.trim().isEmpty()) {
                    result = "§c翻译失败: 网络或未知错误";
                }
                
                // 无论结果是成功还是失败信息，都存入缓存
                ItemTooltipTranslationHandler.translationCache.put(itemToTranslate, result);
            } finally {
                ItemTooltipTranslationHandler.pendingTranslations.remove(itemToTranslate);
            }
        }).start();
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