package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.notes.GuiNote;
import com.zihaomc.ghost.utils.NiuTransUtil;
import com.zihaomc.ghost.LangUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KeybindHandler {

    public static KeyBinding toggleAutoSneak;
    public static KeyBinding togglePlayerESP;
    public static KeyBinding toggleBedrockMiner;
    public static KeyBinding translateItemKey;
    public static KeyBinding openNoteGui; 

    // 用于在 GuiNote 重建（例如，调整窗口大小）期间暂存笔记内容
    private static String noteContentToRestore = null;

    public static void registerKeybinds() {
        String category = "key.ghost.category";

        toggleAutoSneak = new KeyBinding("key.ghost.toggleAutoSneak", Keyboard.KEY_NONE, category);
        togglePlayerESP = new KeyBinding("key.ghost.togglePlayerESP", Keyboard.KEY_NONE, category);
        toggleBedrockMiner = new KeyBinding("key.ghost.toggleBedrockMiner", Keyboard.KEY_NONE, category);
        translateItemKey = new KeyBinding("key.ghost.translateItem", Keyboard.KEY_T, category);
        openNoteGui = new KeyBinding("key.ghost.openNote", Keyboard.KEY_N, category); 

        ClientRegistry.registerKeyBinding(toggleAutoSneak);
        ClientRegistry.registerKeyBinding(togglePlayerESP);
        ClientRegistry.registerKeyBinding(toggleBedrockMiner);
        ClientRegistry.registerKeyBinding(translateItemKey);
        ClientRegistry.registerKeyBinding(openNoteGui); 
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getMinecraft().thePlayer == null) {
            return;
        }

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
        
        if (openNoteGui != null && openNoteGui.isPressed()) {
            if (GhostConfig.enableNoteFeature) {
                // 仅当当前没有打开任何GUI时才打开笔记界面
                if (Minecraft.getMinecraft().currentScreen == null) {
                    Minecraft.getMinecraft().displayGuiScreen(new GuiNote());
                }
            }
        }

        if (Minecraft.getMinecraft().currentScreen == null && translateItemKey != null && translateItemKey.isPressed()) {
            if (translateItemKey.getKeyCode() == Minecraft.getMinecraft().gameSettings.keyBindChat.getKeyCode()) {
                Minecraft.getMinecraft().displayGuiScreen(new GuiChat());
            }
        }
    }

    /**
     * 当任何 GUI 即将打开（或因调整大小而关闭重建）时触发。
     * 我们用它来“抢救”即将被销毁的 GuiNote 实例中的文本。
     */
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        // 仅当修复功能在配置中开启时，此逻辑才生效
        if (!GhostConfig.fixGuiStateLossOnResize) {
            return;
        }
        GuiScreen currentScreen = Minecraft.getMinecraft().currentScreen;
        if (currentScreen instanceof GuiNote) {
            // 当笔记界面即将被关闭并重建时，保存其当前内容
            noteContentToRestore = ((GuiNote) currentScreen).getTextContent();
        }
    }

    /**
     * 当任何 GUI 初始化完成之后触发。
     * 我们用它来将“抢救”回来的文本恢复到新建的 GuiNote 实例中。
     */
    @SubscribeEvent
    public void onGuiInitPost(GuiScreenEvent.InitGuiEvent.Post event) {
        // 仅当修复功能在配置中开启时，此逻辑才生效
        if (!GhostConfig.fixGuiStateLossOnResize) {
            return;
        }
        if (event.gui instanceof GuiNote) {
            if (noteContentToRestore != null) {
                // 将保存的内容恢复到新的 GUI 实例中
                ((GuiNote) event.gui).setTextContentAndInitialize(noteContentToRestore);
                // 恢复后清空，避免影响下一次正常的打开
                noteContentToRestore = null;
            }
        }
    }

    @SubscribeEvent
    public void onGuiKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (event.gui instanceof GuiContainer) {
            if (translateItemKey != null && Keyboard.getEventKeyState() && Keyboard.getEventKey() == translateItemKey.getKeyCode()) {
                if (GuiScreen.isShiftKeyDown()) {
                    if (GuiScreen.isCtrlKeyDown()) {
                        handleClearAllItemTranslations();
                    } else {
                        handleClearItemTranslationPress();
                    }
                } else {
                    handleToggleOrTranslatePress();
                }
                event.setCanceled(true);
            }
        }
    }
    
    private void handleClearAllItemTranslations() {
        if (ItemTooltipTranslationHandler.translationCache.isEmpty()) {
            return;
        }
    
        ItemTooltipTranslationHandler.translationCache.clear();
        ItemTooltipTranslationHandler.temporarilyHiddenItems.clear();
    
        ChatComponentText message = new ChatComponentText(LangUtil.translate("ghost.cache.cleared_all"));
        message.getChatStyle().setColor(EnumChatFormatting.YELLOW);
        Minecraft.getMinecraft().thePlayer.addChatMessage(message);
    }

    private void handleClearItemTranslationPress() {
        String itemName = ItemTooltipTranslationHandler.lastHoveredItemName;
        if (itemName == null || itemName.trim().isEmpty()) {
            return;
        }

        if (ItemTooltipTranslationHandler.translationCache.containsKey(itemName)) {
            ItemTooltipTranslationHandler.translationCache.remove(itemName);
            ItemTooltipTranslationHandler.temporarilyHiddenItems.remove(itemName);
            
            ChatComponentText message = new ChatComponentText(LangUtil.translate("ghost.cache.cleared", itemName));
            message.getChatStyle().setColor(EnumChatFormatting.YELLOW);
            Minecraft.getMinecraft().thePlayer.addChatMessage(message);
        }
    }
    
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
        ChatComponentText requestMessage = new ChatComponentText(LangUtil.translate("ghost.tooltip.requestSent", itemName));
        requestMessage.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
        Minecraft.getMinecraft().thePlayer.addChatMessage(requestMessage);

        new Thread(() -> {
            try {
                String result = NiuTransUtil.translate(textToTranslate);
                List<String> translatedLines;
                
                if (result == null || result.trim().isEmpty()) {
                    translatedLines = Collections.singletonList(EnumChatFormatting.RED + LangUtil.translate("ghost.error.translation.network"));
                } else if (result.startsWith(NiuTransUtil.ERROR_PREFIX)) {
                    String errorContent = result.substring(NiuTransUtil.ERROR_PREFIX.length());
                    translatedLines = Collections.singletonList(EnumChatFormatting.RED + errorContent);
                } else {
                    translatedLines = Arrays.asList(result.split("\n"));
                }
                
                ItemTooltipTranslationHandler.translationCache.put(itemName, translatedLines);

            } finally {
                ItemTooltipTranslationHandler.pendingTranslations.remove(itemName);
            }
        }).start();
    }

    private void sendToggleMessage(String featureNameKey, boolean enabled) {
        String featureName = LangUtil.translate(featureNameKey);
        String statusText = LangUtil.translate(enabled ? "ghost.generic.enabled" : "ghost.generic.disabled");
        
        EnumChatFormatting statusColor = enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED;
        ChatComponentText statusComponent = new ChatComponentText(statusText);
        statusComponent.getChatStyle().setColor(statusColor);
        
        ChatComponentText message = new ChatComponentText("");
        ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
        prefix.getChatStyle().setColor(EnumChatFormatting.AQUA);
        
        ChatComponentTranslation content = new ChatComponentTranslation(
            "ghost.generic.toggle.feedback",
            featureName,
            statusComponent
        );
        
        message.appendSibling(prefix);
        message.appendSibling(content);
        Minecraft.getMinecraft().thePlayer.addChatMessage(message);
    }
}