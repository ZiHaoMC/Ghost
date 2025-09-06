package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
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
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KeybindHandler {

    // ... (registerKeybinds, onClientTick, onGuiKeyboardInput, handleClear... 方法保持不变) ...

    public static KeyBinding toggleAutoSneak;
    public static KeyBinding togglePlayerESP;
    public static KeyBinding toggleBedrockMiner;
    public static KeyBinding translateItemKey;

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

        if (Minecraft.getMinecraft().currentScreen == null && translateItemKey != null && translateItemKey.isPressed()) {
            if (translateItemKey.getKeyCode() == Minecraft.getMinecraft().gameSettings.keyBindChat.getKeyCode()) {
                Minecraft.getMinecraft().displayGuiScreen(new GuiChat());
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
                    // 如果是错误，移除前缀并添加红色
                    String errorContent = result.substring(NiuTransUtil.ERROR_PREFIX.length());
                    translatedLines = Collections.singletonList(EnumChatFormatting.RED + errorContent);
                } else {
                    // 正常结果
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
        ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default") + " ");
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