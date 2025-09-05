package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.NiuTransUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

        // 为“翻译”和“聊天”的按键冲突提供解决方案
        if (Minecraft.getMinecraft().currentScreen == null && translateItemKey != null && translateItemKey.isPressed()) {
            if (translateItemKey.getKeyCode() == Minecraft.getMinecraft().gameSettings.keyBindChat.getKeyCode()) {
                Minecraft.getMinecraft().displayGuiScreen(new GuiChat());
            }
        }
    }

    /**
     * 新增：监听GUI界面的键盘输入事件。
     * 这是处理在物品栏等界面中按键的正确方式。
     */
    @SubscribeEvent
    public void onGuiKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (event.gui instanceof GuiContainer) {
            if (translateItemKey != null && Keyboard.getEventKeyState() && Keyboard.getEventKey() == translateItemKey.getKeyCode()) {
                handleItemTranslationKeyPress();
                event.setCanceled(true);
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
        
        String itemName = ItemTooltipTranslationHandler.lastHoveredItemName;
        if (itemName == null || itemName.trim().isEmpty()) {
            return;
        }

        // v-- 这里是修改的核心 --v

        // 1. 检查翻译是否当前可见
        if (ItemTooltipTranslationHandler.visiblyTranslatedItems.contains(itemName)) {
            // 如果可见，则隐藏它
            ItemTooltipTranslationHandler.visiblyTranslatedItems.remove(itemName);
            return;
        }
        
        // 2. 如果翻译不可见，检查缓存中是否存在
        if (ItemTooltipTranslationHandler.translationCache.containsKey(itemName)) {
            // 如果存在，则显示它（并处理重试失败的情况）
            List<String> cachedValue = ItemTooltipTranslationHandler.translationCache.get(itemName);
            if (cachedValue != null && !cachedValue.isEmpty() && cachedValue.get(0).startsWith("§c")) {
                // 如果是失败记录，从缓存中移除，以便下次可以重新请求
                ItemTooltipTranslationHandler.translationCache.remove(itemName);
            } else {
                // 如果是成功记录，则加入可见列表
                ItemTooltipTranslationHandler.visiblyTranslatedItems.add(itemName);
                return;
            }
        }

        // 3. 如果以上条件都不满足（缓存中没有 或 是一个刚被移除的失败记录），则发起新的翻译请求
        
        // 确保不会重复发送请求
        if (ItemTooltipTranslationHandler.pendingTranslations.contains(itemName)) {
            return;
        }

        List<String> itemLore = ItemTooltipTranslationHandler.lastHoveredItemLore;
        if (itemLore == null) return;
        
        StringBuilder fullTextBuilder = new StringBuilder(itemName);
        for (String line : itemLore) {
            fullTextBuilder.append("\n").append(line);
        }
        String textToTranslate = fullTextBuilder.toString();
        
        if (textToTranslate.trim().isEmpty()) {
            return;
        }
        
        ItemTooltipTranslationHandler.pendingTranslations.add(itemName);
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "已发送翻译请求: " + itemName));

        new Thread(() -> {
            try {
                String result = NiuTransUtil.translate(textToTranslate);
                List<String> translatedLines;
                
                if (result == null || result.trim().isEmpty()) {
                    translatedLines = Collections.singletonList("§c翻译失败: 网络或未知错误");
                } else if (result.startsWith("§c")) {
                    translatedLines = Collections.singletonList(result);
                } else {
                    translatedLines = Arrays.asList(result.split("\n"));
                }
                
                ItemTooltipTranslationHandler.translationCache.put(itemName, translatedLines);
                // 翻译完成后，自动设为可见
                ItemTooltipTranslationHandler.visiblyTranslatedItems.add(itemName);

            } finally {
                ItemTooltipTranslationHandler.pendingTranslations.remove(itemName);
            }
        }).start();
        // ^-- 修改结束 --^
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