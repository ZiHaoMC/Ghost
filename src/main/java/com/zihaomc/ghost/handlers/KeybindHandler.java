package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.NiuTransUtil;
import com.zihaomc.ghost.LangUtil; // <-- 新增 Import
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
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
        // v-- 这里是修改的核心 --v
        if (toggleAutoSneak != null && toggleAutoSneak.isPressed()) {
            boolean newState = !GhostConfig.enableAutoSneakAtEdge;
            GhostConfig.setEnableAutoSneakAtEdge(newState);
            sendToggleMessage("ghost.keybind.toggle.autosneak", newState);
        }

        if (togglePlayerESP != null && togglePlayerESP.isPressed()) {
            boolean newState = !GhostConfig.enablePlayerESP;
            GhostConfig.setEnablePlayerESP(newState);
            sendToggleMessage("ghost.keybind.toggle.playeresp", newState);
        }

        if (toggleBedrockMiner != null && toggleBedrockMiner.isPressed()) {
            boolean newState = !GhostConfig.enableBedrockMiner;
            GhostConfig.setEnableBedrockMiner(newState);
            sendToggleMessage("ghost.keybind.toggle.bedrockminer", newState);
        }
        // ^-- 修改结束 --^

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
                if (GuiScreen.isShiftKeyDown()) {
                    handleClearItemTranslationPress();
                } else {
                    handleToggleOrTranslatePress();
                }
                event.setCanceled(true);
            }
        }
    }
    
    /**
     * 新增：处理清除翻译缓存的按键事件 (Shift + T)。
     */
    private void handleClearItemTranslationPress() {
        String itemName = ItemTooltipTranslationHandler.lastHoveredItemName;
        if (itemName == null || itemName.trim().isEmpty()) {
            return;
        }

        if (ItemTooltipTranslationHandler.translationCache.containsKey(itemName)) {
            ItemTooltipTranslationHandler.translationCache.remove(itemName);
            ItemTooltipTranslationHandler.temporarilyHiddenItems.remove(itemName);
            
            Minecraft.getMinecraft().thePlayer.addChatMessage(
                new ChatComponentText(LangUtil.translate("ghost.cache.cleared", itemName))
            );
        }
    }

    /**
     * 处理物品翻译切换/请求的快捷键按下事件 (T)。
     */
    private void handleToggleOrTranslatePress() {
        if (!GhostConfig.enableItemTranslation) {
            return;
        }
        
        String itemName = ItemTooltipTranslationHandler.lastHoveredItemName;
        if (itemName == null || itemName.trim().isEmpty()) {
            return;
        }

        if (ItemTooltipTranslationHandler.translationCache.containsKey(itemName)) {
            if (ItemTooltipTranslationHandler.temporarilyHiddenItems.contains(itemName)) {
                ItemTooltipTranslationHandler.temporarilyHiddenItems.remove(itemName);
            } else {
                ItemTooltipTranslationHandler.temporarilyHiddenItems.add(itemName);
            }
            return;
        }

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
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.tooltip.requestSent", itemName)));

        new Thread(() -> {
            try {
                String result = NiuTransUtil.translate(textToTranslate);
                List<String> translatedLines;
                
                if (result == null || result.trim().isEmpty()) {
                    translatedLines = Collections.singletonList(LangUtil.translate("ghost.error.translation.network"));
                } else if (result.startsWith("§c")) {
                    translatedLines = Collections.singletonList(result);
                } else {
                    translatedLines = Arrays.asList(result.split("\n"));
                }
                
                ItemTooltipTranslationHandler.translationCache.put(itemName, translatedLines);

            } finally {
                ItemTooltipTranslationHandler.pendingTranslations.remove(itemName);
            }
        }).start();
    }

    /**
     * 向玩家发送功能切换状态的聊天消息。
     * @param featureNameKey 功能名称的语言文件键
     * @param enabled 功能的新状态 (true 为启用, false 为禁用)
     */
    private void sendToggleMessage(String featureNameKey, boolean enabled) {
        EnumChatFormatting statusColor = enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED;
        String featureName = LangUtil.translate(featureNameKey);
        String statusText = LangUtil.translate(enabled ? "ghost.generic.enabled" : "ghost.generic.disabled");

        // 构建带颜色代码的文本
        String statusPart = statusColor + statusText;
        String formattedMessage = LangUtil.translate("ghost.generic.toggle.feedback", featureName, statusPart);
        
        ChatComponentText message = new ChatComponentText("");
        ChatComponentText prefix = new ChatComponentText("[Ghost] ");
        prefix.getChatStyle().setColor(EnumChatFormatting.AQUA);

        // LangUtil 不会解析颜色代码，所以我们用 ChatComponentText 来处理
        ChatComponentText content = new ChatComponentText(formattedMessage);
        
        message.appendSibling(prefix);
        message.appendSibling(content);

        Minecraft.getMinecraft().thePlayer.addChatMessage(message);
    }
}