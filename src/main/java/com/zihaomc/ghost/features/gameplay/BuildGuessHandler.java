package com.zihaomc.ghost.features.gameplay;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel 你建我猜辅助工具 - 精准匹配版
 * 
 * 修正内容：
 * - 恢复了对空格的严格匹配：线索中的空格必须与词库中的空格位置一致。
 * - 包含 AFK 挂机、自动排队、建筑者跳过、热更新等所有功能。
 */
public class BuildGuessHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    
    private static final String CLUE_KEYWORD = "the theme is";
    private static final Pattern REVEAL_PATTERN = Pattern.compile("the theme was: (.+)", Pattern.CASE_INSENSITIVE);
    private static final String GAME_COMMAND = "/play build_battle_guess_the_build";
    
    private static String lastNormalizedClue = "";
    private static String lastSentWord = ""; 
    
    // AFK 状态
    private int delayTicks = 0;
    private boolean isQueuing = false;
    private final Random random = new Random();

    // ==================================================================================
    // 1. 界面检测：跳过建筑者环节
    // ==================================================================================
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!GhostConfig.BuildGuess.enableBuildGuess || !GhostConfig.BuildGuess.enableAfkMode) return;
        
        if (event.gui instanceof GuiChest) {
            GuiChest chest = (GuiChest) event.gui;
            if (chest.inventorySlots instanceof ContainerChest) {
                ContainerChest container = (ContainerChest) chest.inventorySlots;
                IInventory lowerChestInventory = container.getLowerChestInventory();
                
                String title = lowerChestInventory.getDisplayName().getUnformattedText();
                
                if (title != null && (title.contains("Select a theme to build") || title.contains("Select a Theme"))) {
                    LogUtil.info("检测到 [建筑者选词界面]，正在自动跳过...");
                    event.setCanceled(true);
                    mc.thePlayer.sendChatMessage("/lobby");
                    scheduleRejoin(60);
                }
            }
        }
    }

    // ==================================================================================
    // 2. 循环检测：自动排队与防挂机
    // ==================================================================================
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null) return;
        if (!GhostConfig.BuildGuess.enableBuildGuess || !GhostConfig.BuildGuess.enableAfkMode) return;

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (isQueuing) {
            isQueuing = false;
            LogUtil.info("正在加入新游戏...");
            mc.thePlayer.sendChatMessage(GAME_COMMAND);
            delayTicks = 100;
        }

        if (mc.currentScreen == null && mc.thePlayer.ticksExisted % 100 == 0) {
            mc.thePlayer.rotationYaw += (random.nextFloat() - 0.5f) * 4;
        }
    }

    // ==================================================================================
    // 3. 聊天处理：线索匹配、新词学习
    // ==================================================================================
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (!GhostConfig.BuildGuess.enableBuildGuess || mc.thePlayer == null) return;

        String rawText = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        String lowerText = rawText.toLowerCase();

        // --- 获取线索 ---
        if (event.type == 2 && lowerText.contains(CLUE_KEYWORD)) {
            int isIdx = lowerText.indexOf("is");
            if (isIdx != -1) {
                String cluePart = rawText.substring(isIdx + 2);
                if (cluePart.trim().startsWith(":")) {
                    cluePart = cluePart.replaceFirst(":", "");
                }
                
                String normalizedClue = normalizeHypixelClue(cluePart);
                
                if (normalizedClue.equals(lastNormalizedClue)) return;
                lastNormalizedClue = normalizedClue;
                
                processClue(normalizedClue);
            }
        } 
        // --- 结果揭晓与收集 ---
        else if (event.type == 0) {
            if (lowerText.contains("the theme was")) {
                Matcher matcher = REVEAL_PATTERN.matcher(rawText);
                if (matcher.find()) {
                    String revealedWord = matcher.group(1).trim();
                    // 记录时保留原始格式（包括空格、连字符、撇号）
                    revealedWord = revealedWord.replaceAll("[^a-zA-Z0-9\\s\\-']", "").trim();
                    checkAndRecordMissingWord(revealedWord);
                    resetState();
                }
            } 
            else if (lowerText.contains("round has ended") || lowerText.contains("correctly guessed")) {
                resetState();
            }
            
            // AFK 自动重排
            if (GhostConfig.BuildGuess.enableAfkMode) {
                if (lowerText.contains("winner") || lowerText.contains("reward summary") || lowerText.contains("play again")) {
                    LogUtil.info("本局结束，5秒后寻找下一局...");
                    scheduleRejoin(100);
                }
                else if (lowerText.contains("sent you to") && lowerText.contains("lobby")) {
                    scheduleRejoin(60);
                }
            }
        }
    }

    private void scheduleRejoin(int ticks) {
        this.isQueuing = true;
        this.delayTicks = ticks;
    }

    private void resetState() {
        lastNormalizedClue = "";
        lastSentWord = "";
    }

/**
     * 【精准版】匹配逻辑
     * 1. ^ 和 $ 锁定长度。
     * 2. 空格 (" ") 必须严格匹配空格。
     * 3. 下划线 ("_") 替换为 \\S (非空白字符)。
     * 
     * 效果：
     * 线索 "____" (无空格) -> 正则 "^\\S\\S\\S\\S$" -> 绝不会匹配 "A B" (含空格)
     * 线索 "_ _" (有空格) -> 正则 "^\\S \\S$" -> 绝不会匹配 "AB" (无空格)
     * 不再使用 "§e" + word，而是使用标准的 ChatStyle 设置颜色。
     */
    private void processClue(String normalizedClue) {
        if (!normalizedClue.contains("_")) return;

        String regexPattern = "^" + normalizedClue.replace("_", "\\S") + "$";
        Pattern pattern = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);

        List<String> matches = new ArrayList<>();
        
        for (String word : BuildGuessWords.WORDS) {
            if (pattern.matcher(word).matches()) {
                matches.add(word);
            }
        }

        if (matches.size() == 1) {
            String answer = matches.get(0);
            if (!answer.equalsIgnoreCase(lastSentWord)) {
                mc.thePlayer.sendChatMessage(answer.toLowerCase());
                lastSentWord = answer;
            }
        } 
        else if (matches.size() > 1 && matches.size() < 10) {
            // 头部提示
            IChatComponent message = new ChatComponentText("§8[Ghost] §b潜在匹配: ");
            
            for (int i = 0; i < matches.size(); i++) {
                String word = matches.get(i);
                
                // 创建纯净的文本组件，不带 §e
                ChatComponentText wordComp = new ChatComponentText(word);
                
                // 使用 Style 设置颜色和点击事件，保证 Hitbox 精确
                ChatStyle style = new ChatStyle();
                style.setColor(EnumChatFormatting.YELLOW); // 设定为黄色
            //    style.setBold(true); // 加粗，让点击更容易 可能会导致偏移，先禁用
                
                // 设置点击事件：直接发送小写单词
                style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, word.toLowerCase()));
                // 设置悬停事件
                style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("§7点击发送: §f" + word)));
                
                wordComp.setChatStyle(style);
                
                // 添加单词组件
                message.appendSibling(wordComp);
                
                // 添加逗号分隔符 (灰色，无点击事件)
                if (i < matches.size() - 1) {
                    ChatComponentText comma = new ChatComponentText(", ");
                    comma.getChatStyle().setColor(EnumChatFormatting.GRAY);
                    message.appendSibling(comma);
                }
            }
            mc.thePlayer.addChatMessage(message);
        }
    }

    /**
     * 【修正】最简线索处理
     * 只去掉首尾空格，完全保留中间的空格结构。
     * 假设 Hypixel 给的是 "____ ___" (Cute Hat)，我们就用 "____ ___" 去匹配。
     */
    private String normalizeHypixelClue(String clue) {
        return clue.trim().toLowerCase();
    }

    private void checkAndRecordMissingWord(String word) {
        if (word == null || word.isEmpty()) return;
        
        for (String w : BuildGuessWords.WORDS) {
            if (w.equalsIgnoreCase(word)) return;
        }
        
        BuildGuessWords.WORDS.add(word);
        LogUtil.info("已热更新词库，新增: " + word);

        File configDir = new File("config/Ghost/");
        if (!configDir.exists()) configDir.mkdirs();
        File missingFile = new File(configDir, "missing_words.txt");
        
        try (FileWriter fw = new FileWriter(missingFile, true)) {
            fw.write(word + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}