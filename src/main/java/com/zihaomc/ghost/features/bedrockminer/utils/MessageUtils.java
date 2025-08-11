package com.zihaomc.ghost.features.bedrockminer.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

public final class MessageUtils {
    private MessageUtils() {}
    public static void printMessage(IChatComponent message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(message);
        }
    }
    public static void printMessage(String message) {
        printMessage(new ChatComponentText(message));
    }
}