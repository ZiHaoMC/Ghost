package com.zihaomc.ghost.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个用于处理 Minecraft 颜色和格式代码的工具类。
 * 主要功能是：
 * 1. 将一个带格式的字符串解析成一系列带有颜色/格式的“片段”(Segment)。
 * 2. 将这些从原始字符串中解析出的格式，按比例应用到一个新的、不同长度的目标字符串上。
 */
public class ColorFormatting {

    /** 匹配 Minecraft 颜色/格式代码的正则表达式 (e.g., §c, §l) */
    private static final Pattern FORMATTING_CODE_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");

    /**
     * 代表一个带有特定格式的文本片段。
     */
    private static class ColorSegment {
        /** 这个片段的颜色/格式代码，例如 "§c§l" */
        final String format;
        /** 这个片段的纯文本内容 */
        final String text;

        ColorSegment(String format, String text) {
            this.format = format;
            this.text = text;
        }
    }

    /**
     * 将一个带格式的 Minecraft 字符串解析成一个 ColorSegment 列表。
     * @param input 原始的、带格式的字符串。
     * @return 一个 ColorSegment 对象的列表。
     */
    private static List<ColorSegment> parse(String input) {
        List<ColorSegment> segments = new ArrayList<>();
        Matcher matcher = FORMATTING_CODE_PATTERN.matcher(input);

        String currentFormat = "";
        int lastMatchEnd = 0;

        // 遍历所有找到的格式代码
        while (matcher.find()) {
            int matchStart = matcher.start();
            
            // 如果在当前匹配和上一个匹配之间有文本，将其作为一个片段添加
            if (matchStart > lastMatchEnd) {
                segments.add(new ColorSegment(currentFormat, input.substring(lastMatchEnd, matchStart)));
            }
            
            // 更新当前格式
            String code = matcher.group();
            char formatChar = Character.toLowerCase(code.charAt(1));
            // 如果是重置代码 'r'，则清空当前所有格式
            if (formatChar == 'r') {
                currentFormat = "";
            } 
            // 如果是颜色代码，则替换掉之前的颜色代码，保留格式代码
            else if ("0123456789abcdef".indexOf(formatChar) != -1) {
                // Java 8 兼容的写法：遍历 currentFormat，只保留非颜色代码的格式
                StringBuilder newFormat = new StringBuilder();
                Matcher existingFormatMatcher = FORMATTING_CODE_PATTERN.matcher(currentFormat);
                while(existingFormatMatcher.find()) {
                    String existingCode = existingFormatMatcher.group();
                    char existingChar = Character.toLowerCase(existingCode.charAt(1));
                    // 只保留样式代码 (k, l, m, n, o, r)，丢弃颜色代码
                    if ("klmnor".indexOf(existingChar) != -1) {
                        newFormat.append(existingCode);
                    }
                }
                currentFormat = newFormat.toString() + code;
            } 
            // 否则是格式代码 (k, l, m, n, o)，直接添加
            else {
                currentFormat += code;
            }
            
            lastMatchEnd = matcher.end();
        }

        // 添加最后一个片段（最后一个格式代码到字符串末尾的文本）
        if (lastMatchEnd < input.length()) {
            segments.add(new ColorSegment(currentFormat, input.substring(lastMatchEnd)));
        }

        return segments;
    }

    /**
     * 核心方法：将源字符串的颜色格式，按比例应用到目标字符串上。
     * @param sourceFormatted 原始的、带格式的源字符串 (e.g., "§eDiamond §aBlock")
     * @param targetUnformatted 翻译后的、不带格式的目标字符串 (e.g., "钻石块")
     * @return 一个新的、将格式应用到目标字符串上的结果 (e.g., "§e钻石§a块")
     */
    public static String reapply(String sourceFormatted, String targetUnformatted) {
        if (sourceFormatted == null || targetUnformatted == null || targetUnformatted.isEmpty()) {
            return targetUnformatted;
        }

        List<ColorSegment> sourceSegments = parse(sourceFormatted);
        if (sourceSegments.isEmpty()) {
            return targetUnformatted;
        }
        
        // 计算原始纯文本的总长度
        int sourceUnformattedLength = 0;
        for (ColorSegment s : sourceSegments) {
            sourceUnformattedLength += s.text.length();
        }

        if (sourceUnformattedLength == 0) {
            // 如果原始文本没有内容，只保留格式
            return sourceSegments.get(0).format + targetUnformatted;
        }
        
        StringBuilder result = new StringBuilder();
        int targetIndex = 0;

        for (int i = 0; i < sourceSegments.size(); i++) {
            ColorSegment segment = sourceSegments.get(i);
            result.append(segment.format);
            
            // 计算这个片段在目标字符串中应该占据的长度
            // 使用浮点数计算以获得更精确的比例
            double proportion = (double) segment.text.length() / sourceUnformattedLength;
            int segmentLengthInTarget = (int) Math.round(proportion * targetUnformatted.length());

            // 特殊处理：确保最后一个片段能“吃掉”所有剩余的字符，以防浮点数舍入误差
            if (i == sourceSegments.size() - 1) {
                segmentLengthInTarget = targetUnformatted.length() - targetIndex;
            }
            
            int endIndex = Math.min(targetIndex + segmentLengthInTarget, targetUnformatted.length());
            
            if (targetIndex < endIndex) {
                result.append(targetUnformatted.substring(targetIndex, endIndex));
            }

            targetIndex = endIndex;
        }

        return result.toString();
    }
}