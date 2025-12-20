
/*
 * Copyright (c) 2025 ZiHaoMC.
 * Licensed under the MIT License.
 */
 
package com.zihaomc.ghost.features.gameplay;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel 你建我猜辅助工具。
 * 核心逻辑：精准识别单词长度和空格分词，所有发送内容自动转为小写，防止针对同一线索重复发送。
 */
public class BuildGuessHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    
    private static final String CLUE_KEYWORD = "the theme is";
    // 识别答案揭晓，剔除末尾所有标点符号和后缀
    private static final Pattern REVEAL_PATTERN = Pattern.compile("the theme was: (.+)", Pattern.CASE_INSENSITIVE);
    
    private static String lastNormalizedClue = "";
    private static String lastSentWord = ""; 

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (!GhostConfig.BuildGuess.enableBuildGuess || mc.thePlayer == null) return;

        String rawText = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        String lowerText = rawText.toLowerCase();

        // 处理 Action Bar (Type 2)
        if (event.type == 2 && lowerText.contains(CLUE_KEYWORD)) {
            // 提取 "is" 之后的部分
            int isIdx = lowerText.indexOf("is");
            if (isIdx != -1) {
                String cluePart = rawText.substring(isIdx + 2).trim();
                if (cluePart.startsWith(":")) cluePart = cluePart.substring(1).trim();
                
                // 1. 规范化线索 (处理空格和字数)
                String normalizedClue = normalizeHypixelClue(cluePart);
                
                // 2. 防重复：如果线索内容没变（没有新字母出现），则跳过处理
                if (normalizedClue.equals(lastNormalizedClue)) return;
                lastNormalizedClue = normalizedClue;
                
                processClue(normalizedClue);
            }
        } 
        // 处理聊天框 (Type 0)
        else if (event.type == 0) {
            if (lowerText.contains("the theme was")) {
                Matcher matcher = REVEAL_PATTERN.matcher(rawText);
                if (matcher.find()) {
                    String revealedWord = matcher.group(1).trim();
                    // 彻底剔除末尾标点符号，保留中间空格
                    revealedWord = revealedWord.replaceAll("[^a-zA-Z ]+$", "").trim();
                    
                    checkAndRecordMissingWord(revealedWord);
                    
                    // 游戏结束揭晓答案后，重置发送锁
                    lastNormalizedClue = "";
                    lastSentWord = "";
                }
            } else if (lowerText.contains("round has ended") || lowerText.contains("correctly guessed")) {
                // 回合结束或有人猜中，重置锁以便下一阶段匹配
                lastNormalizedClue = "";
                lastSentWord = "";
            }
        }
    }

    private void processClue(String normalizedClue) {
        // 如果线索已经没有下划线，说明单词已经完整显示，无需处理
        if (!normalizedClue.contains("_")) return;

        // 生成正则表达式进行长度和字符匹配
        String regexPattern = "^" + normalizedClue.replace("_", ".") + "$";
        Pattern pattern = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);

        List<String> matches = new ArrayList<>();
        for (String word : BuildGuessWords.WORDS) {
            // 与词库原词进行正则比对（包含空格和长度校验）
            if (pattern.matcher(word).matches()) {
                matches.add(word);
            }
        }

        if (matches.size() == 1) {
            String answer = matches.get(0);
            // 如果此单词针对当前线索尚未发送过，则执行自动答题
            if (!answer.equalsIgnoreCase(lastSentWord)) {
                // 强制转为全小写发送
                mc.thePlayer.sendChatMessage(answer.toLowerCase());
                lastSentWord = answer;
            }
        } 
        else if (matches.size() > 1 && matches.size() < 10) {
            // 发送可点击的提示组件
            IChatComponent message = new ChatComponentText("§8[Ghost] §b可能的结果: ");
            for (int i = 0; i < matches.size(); i++) {
                String word = matches.get(i);
                ChatComponentText wordComp = new ChatComponentText("§e§l" + word);
                
                wordComp.setChatStyle(new ChatStyle()
                    // 点击执行命令处也加入 toLowerCase() 确保点击发送的也是小写
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, word.toLowerCase()))
                    .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("§7点击发送答案: §f" + word.toLowerCase()))));
                
                message.appendSibling(wordComp);
                if (i < matches.size() - 1) message.appendSibling(new ChatComponentText("§7, "));
            }
            mc.thePlayer.addChatMessage(message);
        }
    }

    /**
     * 将 Hypixel 的原始线索转换为正则友好的规范格式
     */
    private String normalizeHypixelClue(String clue) {
        // 1. 移除括号及倒计时内容 (例如: "(1:20)")
        String noTime = clue.replaceAll("\\(.+?\\)", "").trim();

        // 2. 处理词组的长空格（Hypixel 通常用 3 个或以上空格分隔单词）
        String[] multiWords = noTime.split("\\s{2,}"); 
        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < multiWords.length; i++) {
            // 移除字母或下划线之间模拟间距的单空格
            String singleWordPart = multiWords[i].replace(" ", "");
            sb.append(singleWordPart);
            if (i < multiWords.length - 1) {
                sb.append(" "); // 在多个单词之间保留标准单空格
            }
        }
        
        return sb.toString().toLowerCase();
    }

    private void checkAndRecordMissingWord(String word) {
        if (word == null || word.isEmpty()) return;
        
        // 检查是否已存在于词库中
        boolean exists = false;
        for (String w : BuildGuessWords.WORDS) {
            if (w.equalsIgnoreCase(word)) {
                exists = true;
                break;
            }
        }
        if (exists) return;

        // 记录到文件
        File configDir = new File("config/Ghost/");
        if (!configDir.exists()) configDir.mkdirs();
        File missingFile = new File(configDir, "missing_words.txt");
        
        try (FileWriter fw = new FileWriter(missingFile, true)) {
            fw.write(word + "\n");
            LogUtil.info("BuildGuess 记录未知单词: " + word);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}