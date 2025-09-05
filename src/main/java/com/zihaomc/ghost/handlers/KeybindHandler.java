package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.NiuTransUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat; // <-- 新增 Import
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

        // v-- 这里是修正的核心 --v
        // 为“翻译”和“聊天”的按键冲突提供解决方案
        // 如果我们在游戏世界里 (没有打开任何GUI)，并且我们的翻译键被按下了...
        if (Minecraft.getMinecraft().currentScreen == null && translateItemKey != null && translateItemKey.isPressed()) {
            // ...那么我们检查一下，这个键是不是和原版的聊天键设置成了同一个键。
            if (translateItemKey.getKeyCode() == Minecraft.getMinecraft().gameSettings.keyBindChat.getKeyCode()) {
                // 如果是同一个键，我们就手动帮Minecraft打开聊天栏，以恢复它的正常功能。
                Minecraft.getMinecraft().displayGuiScreen(new GuiChat());
            }
        }
        // ^-- 修正结束 --^
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
                // 取消事件，防止Minecraft执行默认的“打开聊天栏”操作（如果按键也是T）
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
        
        // 从 Handler 获取物品名称和完整的描述列表
        String itemName = ItemTooltipTranslationHandler.lastHoveredItemName;
        List<String> itemLore = ItemTooltipTranslationHandler.lastHoveredItemLore;

        // 如果名称或描述无效，则不处理
        if (itemName == null || itemName.trim().isEmpty() || itemLore == null) {
            return;
        }
        
        // 如果已在缓存或翻译中，则不处理
        if (ItemTooltipTranslationHandler.translationCache.containsKey(itemName) ||
            ItemTooltipTranslationHandler.pendingTranslations.contains(itemName)) {
            return;
        }
        
        // 1. 将名称和所有描述行合并成一个由换行符分隔的字符串
        StringBuilder fullTextBuilder = new StringBuilder(itemName);
        for (String line : itemLore) {
            fullTextBuilder.append("\n").append(line);
        }
        String textToTranslate = fullTextBuilder.toString();
        
        // 如果合并后为空（虽然不太可能），则不处理
        if (textToTranslate.trim().isEmpty()) {
            return;
        }
        
        // 使用物品名称作为键，加入“等待中”列表
        ItemTooltipTranslationHandler.pendingTranslations.add(itemName);
        Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "已发送翻译请求: " + itemName));

        new Thread(() -> {
            try {
                String result = NiuTransUtil.translate(textToTranslate);

                List<String> translatedLines;
                
                if (result == null || result.trim().isEmpty()) {
                    // 网络或未知错误
                    translatedLines = Collections.singletonList("§c翻译失败: 网络或未知错误");
                } else if (result.startsWith("§c")) {
                    // API返回的明确错误
                    translatedLines = Collections.singletonList(result);
                } else {
                    // 翻译成功，按换行符拆分成列表
                    translatedLines = Arrays.asList(result.split("\n"));
                }
                
                // 将翻译后的行列表存入缓存
                ItemTooltipTranslationHandler.translationCache.put(itemName, translatedLines);

            } finally {
                // 无论结果如何，都从“等待中”列表移除
                ItemTooltipTranslationHandler.pendingTranslations.remove(itemName);
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