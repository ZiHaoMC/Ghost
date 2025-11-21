package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.automine.AutoMineHandler;
import com.zihaomc.ghost.features.note.GuiNote;
import com.zihaomc.ghost.utils.ColorFormatting;
import com.zihaomc.ghost.utils.TranslationUtil;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KeybindHandler {

    public static KeyBinding toggleAutoSneak;
    public static KeyBinding togglePlayerESP;
    public static KeyBinding toggleBedrockMiner;
    public static KeyBinding translateItemKey;
    public static KeyBinding openNoteGui;
    public static KeyBinding toggleAutoMine; 

    private static String noteContentToRestore = null;

    public static void registerKeybinds() {
        String category = "key.ghost.category";

        toggleAutoSneak = new KeyBinding("key.ghost.toggleAutoSneak", Keyboard.KEY_NONE, category);
        togglePlayerESP = new KeyBinding("key.ghost.togglePlayerESP", Keyboard.KEY_NONE, category);
        toggleBedrockMiner = new KeyBinding("key.ghost.toggleBedrockMiner", Keyboard.KEY_NONE, category);
        translateItemKey = new KeyBinding("key.ghost.translateItem", Keyboard.KEY_T, category);
        openNoteGui = new KeyBinding("key.ghost.openNote", Keyboard.KEY_N, category);
        toggleAutoMine = new KeyBinding("key.ghost.toggleAutoMine", Keyboard.KEY_NONE, category);

        ClientRegistry.registerKeyBinding(toggleAutoSneak);
        ClientRegistry.registerKeyBinding(togglePlayerESP);
        ClientRegistry.registerKeyBinding(toggleBedrockMiner);
        ClientRegistry.registerKeyBinding(translateItemKey);
        ClientRegistry.registerKeyBinding(openNoteGui);
        ClientRegistry.registerKeyBinding(toggleAutoMine);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getMinecraft().thePlayer == null) {
            return;
        }

        if (toggleAutoSneak != null && toggleAutoSneak.isPressed()) {
            boolean newState = !GhostConfig.AutoSneak.enableAutoSneakAtEdge;
            GhostConfig.setEnableAutoSneakAtEdge(newState);
            sendToggleMessage("ghost.keybind.toggle.autosneak", newState);
        }

        if (togglePlayerESP != null && togglePlayerESP.isPressed()) {
            boolean newState = !GhostConfig.PlayerESP.enablePlayerESP;
            GhostConfig.setEnablePlayerESP(newState);
            sendToggleMessage("ghost.keybind.toggle.playeresp", newState);
        }

        if (toggleBedrockMiner != null && toggleBedrockMiner.isPressed()) {
            boolean newState = !GhostConfig.BedrockMiner.enableBedrockMiner;
            GhostConfig.setEnableBedrockMiner(newState);
            sendToggleMessage("ghost.keybind.toggle.bedrockminer", newState);
        }
        
        if (openNoteGui != null && openNoteGui.isPressed()) {
            if (GhostConfig.NoteTaking.enableNoteFeature) {
                if (Minecraft.getMinecraft().currentScreen == null) {
                    Minecraft.getMinecraft().displayGuiScreen(new GuiNote());
                }
            }
        }

        if (toggleAutoMine != null && toggleAutoMine.isPressed()) {
            if (Minecraft.getMinecraft().currentScreen == null) {
                AutoMineHandler.toggle();
            }
        }

        if (Minecraft.getMinecraft().currentScreen == null && translateItemKey != null && translateItemKey.isPressed()) {
            if (translateItemKey.getKeyCode() == Minecraft.getMinecraft().gameSettings.keyBindChat.getKeyCode()) {
                Minecraft.getMinecraft().displayGuiScreen(new GuiChat());
            }
        }
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!GhostConfig.GuiTweaks.fixGuiStateLossOnResize) {
            return;
        }
        GuiScreen currentScreen = Minecraft.getMinecraft().currentScreen;
        if (currentScreen instanceof GuiNote) {
            noteContentToRestore = ((GuiNote) currentScreen).getTextContent();
        }
    }

    @SubscribeEvent
    public void onGuiInitPost(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!GhostConfig.GuiTweaks.fixGuiStateLossOnResize) {
            return;
        }
        if (event.gui instanceof GuiNote) {
            if (noteContentToRestore != null) {
                ((GuiNote) event.gui).setTextContentAndInitialize(noteContentToRestore);
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
        if (ItemTooltipTranslationHandler.translationCache.isEmpty() && ItemTooltipTranslationHandler.hiddenTranslations.isEmpty()) {
            return;
        }
    
        ItemTooltipTranslationHandler.translationCache.clear();
        ItemTooltipTranslationHandler.pendingTranslations.clear();
        ItemTooltipTranslationHandler.hiddenTranslations.clear();
    
        ChatComponentText message = new ChatComponentText(LangUtil.translate("ghost.cache.cleared_all"));
        message.getChatStyle().setColor(EnumChatFormatting.YELLOW);
        Minecraft.getMinecraft().thePlayer.addChatMessage(message);
    }

    private void handleClearItemTranslationPress() {
        String itemName = ItemTooltipTranslationHandler.lastHoveredItemName;
        if (itemName == null || itemName.trim().isEmpty()) {
            return;
        }
    
        boolean changed = false;
    
        if (ItemTooltipTranslationHandler.translationCache.remove(itemName) != null) {
            changed = true;
        }
        if (ItemTooltipTranslationHandler.hiddenTranslations.remove(itemName)) {
            changed = true;
        }
        if (ItemTooltipTranslationHandler.pendingTranslations.remove(itemName)) {
            changed = true;
        }
        
        if (changed) {
            ChatComponentText message = new ChatComponentText(LangUtil.translate("ghost.cache.cleared", itemName));
            message.getChatStyle().setColor(EnumChatFormatting.YELLOW);
            Minecraft.getMinecraft().thePlayer.addChatMessage(message);
        }
    }
    
    public void handleToggleOrTranslatePress() {
        if (!GhostConfig.Translation.enableItemTranslation && !GhostConfig.Translation.enableAutomaticTranslation) {
            return;
        }
        
        String originalFormattedName = ItemTooltipTranslationHandler.lastHoveredItemOriginalName;
        String unformattedName = ItemTooltipTranslationHandler.lastHoveredItemName;

        if (unformattedName == null || unformattedName.trim().isEmpty()) {
            return;
        }

        if (ItemTooltipTranslationHandler.translationCache.containsKey(unformattedName)) {
            if (ItemTooltipTranslationHandler.hiddenTranslations.contains(unformattedName)) {
                ItemTooltipTranslationHandler.hiddenTranslations.remove(unformattedName);
            } else {
                ItemTooltipTranslationHandler.hiddenTranslations.add(unformattedName);
            }
            return;
        }

        if (ItemTooltipTranslationHandler.pendingTranslations.contains(unformattedName)) {
            return;
        }

        List<String> unformattedLore = ItemTooltipTranslationHandler.lastHoveredItemLore;
        List<String> originalFormattedLore = ItemTooltipTranslationHandler.lastHoveredItemOriginalLore;
        if (unformattedLore == null || originalFormattedLore == null) return;
        
        StringBuilder plainTextBuilder = new StringBuilder(unformattedName);
        for (String line : unformattedLore) {
            plainTextBuilder.append("\n").append(line);
        }
        String textToTranslate = plainTextBuilder.toString();
        
        if (textToTranslate.trim().isEmpty()) {
            return;
        }
        
        ItemTooltipTranslationHandler.pendingTranslations.add(unformattedName);
        ChatComponentText requestMessage = new ChatComponentText(LangUtil.translate("ghost.tooltip.requestSent", unformattedName));
        requestMessage.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
        Minecraft.getMinecraft().thePlayer.addChatMessage(requestMessage);

        // 使用线程池执行
        TranslationUtil.runAsynchronously(() -> {
            try {
                String translationResult = TranslationUtil.translate(textToTranslate);
                List<String> finalFormattedLines = new ArrayList<>();
                
                if (translationResult == null || translationResult.trim().isEmpty()) {
                    finalFormattedLines.add(EnumChatFormatting.RED + LangUtil.translate("ghost.error.translation.network"));
                } else if (translationResult.startsWith(TranslationUtil.ERROR_PREFIX)) {
                    String errorContent = translationResult.substring(TranslationUtil.ERROR_PREFIX.length());
                    finalFormattedLines.add(EnumChatFormatting.RED + errorContent);
                } else {
                    String[] translatedParts = translationResult.split("\n");
                    
                    String reformattedName = ColorFormatting.reapply(originalFormattedName, translatedParts[0]);
                    finalFormattedLines.add(reformattedName);
                    
                    int loreLinesToProcess = Math.min(originalFormattedLore.size(), translatedParts.length - 1);
                    for (int i = 0; i < loreLinesToProcess; i++) {
                        String originalLoreLine = originalFormattedLore.get(i);
                        String translatedLoreLine = translatedParts[i + 1];
                        finalFormattedLines.add(ColorFormatting.reapply(originalLoreLine, translatedLoreLine));
                    }
                    if (translatedParts.length - 1 > loreLinesToProcess) {
                        for (int i = loreLinesToProcess + 1; i < translatedParts.length; i++) {
                            finalFormattedLines.add(translatedParts[i]);
                        }
                    }
                }
                
                ItemTooltipTranslationHandler.translationCache.put(unformattedName, finalFormattedLines);

            } finally {
                ItemTooltipTranslationHandler.pendingTranslations.remove(unformattedName);
            }
        });
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