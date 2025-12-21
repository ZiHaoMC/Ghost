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
 * Hypixel 你建我猜辅助工具 - 完整版
 * 功能：
 * 1. 根据 Action Bar 线索自动匹配词汇并发送。
 * 2. 游戏结束自动记录新词汇到文件并热更新内存。
 * 3. AFK 挂机模式：自动排队、防掉线、跳过建筑者环节。
 */
public class BuildGuessHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    
    // 关键词与正则
    private static final String CLUE_KEYWORD = "the theme is";
    private static final Pattern REVEAL_PATTERN = Pattern.compile("the theme was: (.+)", Pattern.CASE_INSENSITIVE);
    private static final String GAME_COMMAND = "/play build_battle_guess_the_build";
    
    // 状态缓存
    private static String lastNormalizedClue = "";
    private static String lastSentWord = ""; 
    
    // AFK 状态管理
    private int delayTicks = 0;
    private boolean isQueuing = false;
    private final Random random = new Random();

    // ==================================================================================
    // 1. 界面检测：跳过建筑者环节
    // ==================================================================================
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        // 仅在 AFK 模式开启时生效
        if (!GhostConfig.BuildGuess.enableBuildGuess || !GhostConfig.BuildGuess.enableAfkMode) return;
        
        if (event.gui instanceof GuiChest) {
            GuiChest chest = (GuiChest) event.gui;
            if (chest.inventorySlots instanceof ContainerChest) {
                ContainerChest container = (ContainerChest) chest.inventorySlots;
                IInventory lowerChestInventory = container.getLowerChestInventory();
                
                // 获取界面标题
                String title = lowerChestInventory.getDisplayName().getUnformattedText();
                
                // 【核心检测】如果标题包含 "Select a theme to build!"
                if (title != null && (title.contains("Select a theme to build") || title.contains("Select a Theme"))) {
                    LogUtil.info("检测到 [建筑者选词界面]，正在自动跳过...");
                    
                    // 1. 阻止界面打开 (不让玩家看到)
                    event.setCanceled(true);
                    
                    // 2. 发送 /lobby 退出当前游戏
                    mc.thePlayer.sendChatMessage("/lobby");
                    
                    // 3. 3秒后重新排队 (给服务器一点反应时间)
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

        // 倒计时处理
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        // 如果在排队状态，且倒计时结束 -> 加入游戏
        if (isQueuing) {
            isQueuing = false;
            LogUtil.info("正在加入新游戏...");
            mc.thePlayer.sendChatMessage(GAME_COMMAND);
            // 给较长的冷却防止刷屏 (5秒)
            delayTicks = 100;
        }

        // 简单的防踢逻辑：每 5 秒轻微转动视角
        // 仅在没有打开 GUI 时执行，防止干扰正常操作
        if (mc.currentScreen == null && mc.thePlayer.ticksExisted % 100 == 0) {
            mc.thePlayer.rotationYaw += (random.nextFloat() - 0.5f) * 4;
        }
    }

    // ==================================================================================
    // 3. 聊天处理：线索匹配、新词学习、游戏结束检测
    // ==================================================================================
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (!GhostConfig.BuildGuess.enableBuildGuess || mc.thePlayer == null) return;

        String rawText = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        String lowerText = rawText.toLowerCase();

        // --- A. 游戏进行中：获取线索 (Action Bar) ---
        if (event.type == 2 && lowerText.contains(CLUE_KEYWORD)) {
            int isIdx = lowerText.indexOf("is");
            if (isIdx != -1) {
                // 提取线索
                String cluePart = rawText.substring(isIdx + 2);
                if (cluePart.trim().startsWith(":")) {
                    cluePart = cluePart.replaceFirst(":", "");
                }
                
                String normalizedClue = normalizeHypixelClue(cluePart);
                
                // 防重复
                if (normalizedClue.equals(lastNormalizedClue)) return;
                lastNormalizedClue = normalizedClue;
                
                processClue(normalizedClue);
            }
        } 
        // --- B. 聊天栏消息：结果揭晓、回合结束、游戏结束 ---
        else if (event.type == 0) {
            // 1. 收集答案 (The theme was: Word)
            if (lowerText.contains("the theme was")) {
                Matcher matcher = REVEAL_PATTERN.matcher(rawText);
                if (matcher.find()) {
                    String revealedWord = matcher.group(1).trim();
                    // 清洗并保存 (保留空格、连字符、撇号)
                    revealedWord = revealedWord.replaceAll("[^a-zA-Z0-9\\s\\-']", "").trim();
                    checkAndRecordMissingWord(revealedWord);
                    
                    resetState();
                }
            } 
            // 2. 回合结束 (Round has ended / Correctly guessed)
            else if (lowerText.contains("round has ended") || lowerText.contains("correctly guessed")) {
                resetState();
            }
            
            // 3. AFK 模式：检测游戏结束，自动下一局
            if (GhostConfig.BuildGuess.enableAfkMode) {
                // 匹配胜利信息或奖励汇总
                if (lowerText.contains("winner") || lowerText.contains("reward summary") || lowerText.contains("play again")) {
                    LogUtil.info("本局结束，5秒后寻找下一局...");
                    scheduleRejoin(100); // 5秒
                }
                // 意外回到大厅
                else if (lowerText.contains("sent you to") && lowerText.contains("lobby")) {
                    scheduleRejoin(60); // 3秒
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
     * 正则匹配逻辑
     * 支持空格匹配 (如 "Hot Dog")
     */
    private void processClue(String normalizedClue) {
        if (!normalizedClue.contains("_")) return;

        // 将下划线转为正则任意字符 (.)，空格保留
        String regexPattern = "^" + normalizedClue.replace("_", ".") + "$";
        Pattern pattern = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);

        List<String> matches = new ArrayList<>();
        // 遍历内存中的词库
        for (String word : BuildGuessWords.WORDS) {
            if (pattern.matcher(word).matches()) {
                matches.add(word);
            }
        }

        // 唯一匹配直接发送
        if (matches.size() == 1) {
            String answer = matches.get(0);
            if (!answer.equalsIgnoreCase(lastSentWord)) {
                mc.thePlayer.sendChatMessage(answer.toLowerCase());
                lastSentWord = answer;
            }
        } 
        // 少量匹配显示提示
        else if (matches.size() > 1 && matches.size() < 10) {
            IChatComponent message = new ChatComponentText("§8[Ghost] §b潜在匹配: ");
            for (int i = 0; i < matches.size(); i++) {
                String word = matches.get(i);
                ChatComponentText wordComp = new ChatComponentText("§e" + word);
                wordComp.setChatStyle(new ChatStyle()
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, word.toLowerCase()))
                    .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("§7点击发送"))));
                
                message.appendSibling(wordComp);
                if (i < matches.size() - 1) message.appendSibling(new ChatComponentText("§7, "));
            }
            mc.thePlayer.addChatMessage(message);
        }
    }

    /**
     * 规范化线索
     * 1. 去除首尾空格
     * 2. 将连续的多个空格合并为一个 (确保正则匹配稳定)
     */
    private String normalizeHypixelClue(String clue) {
        return clue.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * 检查并记录新词 (热更新)
     */
    private void checkAndRecordMissingWord(String word) {
        if (word == null || word.isEmpty()) return;
        
        // 1. 检查内存
        for (String w : BuildGuessWords.WORDS) {
            if (w.equalsIgnoreCase(word)) return;
        }
        
        // 2. 热更新内存
        BuildGuessWords.WORDS.add(word);
        LogUtil.info("已热更新词库，新增: " + word);

        // 3. 写入文件
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