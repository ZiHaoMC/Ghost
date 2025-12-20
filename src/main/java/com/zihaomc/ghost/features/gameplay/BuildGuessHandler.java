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

public class BuildGuessHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    
    private static final String CLUE_KEYWORD = "the theme is";
    private static final Pattern REVEAL_PATTERN = Pattern.compile("the theme was: (.+)", Pattern.CASE_INSENSITIVE);
    
    private static String lastNormalizedClue = "";
    private static String lastSentWord = ""; 

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (!GhostConfig.BuildGuess.enableBuildGuess || mc.thePlayer == null) return;

        String rawText = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        String lowerText = rawText.toLowerCase();

        // --- 游戏进行中获取线索 ---
        if (event.type == 2 && lowerText.contains(CLUE_KEYWORD)) {
            int isIdx = lowerText.indexOf("is");
            if (isIdx != -1) {
                // 截取 "is" 后面的部分
                String cluePart = rawText.substring(isIdx + 2);
                if (cluePart.trim().startsWith(":")) {
                    cluePart = cluePart.replaceFirst(":", "");
                }
                
                // 1. 简单规范化 (只去头尾空格 + 转小写)
                String normalizedClue = normalizeHypixelClue(cluePart);
                
                // 2. 防重复处理
                if (normalizedClue.equals(lastNormalizedClue)) return;
                lastNormalizedClue = normalizedClue;
                
                processClue(normalizedClue);
            }
        } 
        // --- 游戏/回合结束逻辑 ---
        else if (event.type == 0) {
            if (lowerText.contains("the theme was")) {
                Matcher matcher = REVEAL_PATTERN.matcher(rawText);
                if (matcher.find()) {
                    String revealedWord = matcher.group(1).trim();
                    // 记录时允许空格，只过滤特殊符号
                    revealedWord = revealedWord.replaceAll("[^a-zA-Z0-9\\s]", "").trim();
                    checkAndRecordMissingWord(revealedWord);
                    resetState();
                }
            } else if (lowerText.contains("round has ended") || lowerText.contains("correctly guessed")) {
                resetState();
            }
        }
    }

    private void resetState() {
        lastNormalizedClue = "";
        lastSentWord = "";
    }

    private void processClue(String normalizedClue) {
        if (!normalizedClue.contains("_")) return;

        // 生成正则：将下划线替换为任意字符(.)，空格保持原样
        // 例如 "h_t d_g" -> "^h.t d.g$"
        String regexPattern = "^" + normalizedClue.replace("_", ".") + "$";
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

    // 只需要最简单的处理
    private String normalizeHypixelClue(String clue) {
        return clue.trim().toLowerCase();
    }

    private void checkAndRecordMissingWord(String word) {
        if (word == null || word.isEmpty()) return;
        
        for (String w : BuildGuessWords.WORDS) {
            if (w.equalsIgnoreCase(word)) return;
        }

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