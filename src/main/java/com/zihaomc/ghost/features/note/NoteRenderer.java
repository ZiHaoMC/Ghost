package com.zihaomc.ghost.features.note;

import com.google.common.collect.Lists;
import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责所有与笔记GUI相关的渲染逻辑。
 * 包括文本换行、绘制、光标、选区等。
 */
public class NoteRenderer {

    private final FontRenderer fontRenderer;
    private final int wrappingWidth;
    private final int textAreaX;
    
    private List<String> renderedLines = new ArrayList<>();
    private int[] lineStartIndices = new int[0];
    private final List<Integer> charXPositions = new ArrayList<>();

    public NoteRenderer(FontRenderer fontRenderer, int textAreaX, int wrappingWidth) {
        this.fontRenderer = fontRenderer;
        this.textAreaX = textAreaX;
        this.wrappingWidth = wrappingWidth;
    }
    
    public List<String> getRenderedLines() { return renderedLines; }

    public void updateLines(String textContent) {
        if (textContent == null) return;
        
        List<String> newLines = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        
        if (textContent.isEmpty()) {
            newLines.add("");
            indices.add(0);
        } else {
            int currentPos = 0;
            while (currentPos < textContent.length()) {
                indices.add(currentPos);
                String remaining = textContent.substring(currentPos);
                int lineLength = computeMaxCharsForWidth(remaining, wrappingWidth);

                if (lineLength <= 0 && currentPos < textContent.length()) {
                     lineLength = textContent.charAt(currentPos) == '\n' ? 0 : 1;
                }
                
                newLines.add(textContent.substring(currentPos, currentPos + lineLength));
                currentPos += lineLength;

                if (currentPos < textContent.length() && textContent.charAt(currentPos) == '\n') {
                    currentPos++;
                }
            }
        }
        
        if (newLines.isEmpty() || (textContent.length() > 0 && textContent.endsWith("\n"))) {
            newLines.add("");
            indices.add(textContent.length());
        }

        this.renderedLines = newLines;
        this.lineStartIndices = new int[indices.size() + 1];
        for (int i = 0; i < indices.size(); i++) {
            this.lineStartIndices[i] = indices.get(i);
        }
        this.lineStartIndices[indices.size()] = textContent.length();
    }

    private int computeMaxCharsForWidth(String text, int width) {
        if (text.isEmpty()) return 0;
        int manualNewlinePos = text.indexOf('\n');
        
        for (int i = 1; i <= text.length(); ++i) {
            if (manualNewlinePos != -1 && i > manualNewlinePos) {
                return manualNewlinePos;
            }
            String sub = text.substring(0, i);
            if (this.fontRenderer.getStringWidth(sub) > width) {
                return i - 1;
            }
        }
        return (manualNewlinePos != -1) ? manualNewlinePos : text.length();
    }
    
    public void drawStringAndCachePositions(String text, int x, int y, int color) {
        this.charXPositions.clear();
        float currentX = (float) x;
        String lineToRender = text;
        float scale = 1.0f;
        boolean isBoldTitle = false;

        if (GhostConfig.NoteTaking.enableMarkdownRendering) {
            if (lineToRender.startsWith("# ")) { scale = 1.5f; isBoldTitle = true; lineToRender = lineToRender.substring(2); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); }
            else if (lineToRender.startsWith("## ")) { scale = 1.2f; isBoldTitle = true; lineToRender = lineToRender.substring(3); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); }
            else if (lineToRender.startsWith("### ")) { isBoldTitle = true; lineToRender = lineToRender.substring(4); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); }
            else if (lineToRender.startsWith("- ") || lineToRender.startsWith("* ")) {
                String bullet = "• ";
                this.fontRenderer.drawStringWithShadow(bullet, currentX, (float)y, color);
                currentX += this.fontRenderer.getStringWidth(bullet);
                lineToRender = lineToRender.substring(2);
                this.charXPositions.add(x); this.charXPositions.add(x);
            }
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(currentX, y, 0);
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.translate(-currentX, -y, 0);

        String activeFormat = "";
        boolean isBold = false, isItalic = false, isStrikethrough = false;
        
        for (int i = 0; i < lineToRender.length();) {
            this.charXPositions.add((int)Math.round(currentX));
            char currentChar = lineToRender.charAt(i);
            char nextChar = (i + 1 < lineToRender.length()) ? lineToRender.charAt(i + 1) : '\0';

            boolean isColorPrefix = (GhostConfig.NoteTaking.enableAmpersandColorCodes && currentChar == '&') || currentChar == '§';
            boolean isColorCode = GhostConfig.NoteTaking.enableColorRendering && isColorPrefix && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(nextChar)) != -1;
            boolean isBoldMd = GhostConfig.NoteTaking.enableMarkdownRendering && currentChar == '*' && nextChar == '*';
            boolean isStrikeMd = GhostConfig.NoteTaking.enableMarkdownRendering && currentChar == '~' && nextChar == '~';
            boolean isItalicMd = GhostConfig.NoteTaking.enableMarkdownRendering && currentChar == '*';

            if (isColorCode) {
                if (Character.toLowerCase(nextChar) == 'r') activeFormat = "";
                else activeFormat += "§" + nextChar;
                i += 2;
                this.charXPositions.add((int)Math.round(currentX));
            } else if (isBoldMd) { isBold = !isBold; i += 2; this.charXPositions.add((int)Math.round(currentX)); }
            else if (isStrikeMd) { isStrikethrough = !isStrikethrough; i += 2; this.charXPositions.add((int)Math.round(currentX)); }
            else if (isItalicMd) { isItalic = !isItalic; i += 1; }
            else {
                StringBuilder finalFormat = new StringBuilder(activeFormat);
                if (isItalic) finalFormat.append(EnumChatFormatting.ITALIC);
                if (isBold || isBoldTitle) finalFormat.append(EnumChatFormatting.BOLD);
                if (isStrikethrough) finalFormat.append(EnumChatFormatting.STRIKETHROUGH);
                String formatPrefix = finalFormat.toString();
                String charToRenderWithFormat = formatPrefix + currentChar;

                this.fontRenderer.drawStringWithShadow(charToRenderWithFormat, currentX, (float)y, color);
                
                // --- BUG FIX ---
                // 为 § 和 & 符号赋予一个非零宽度，确保光标能够移动
                int charWidth;
                if (currentChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && currentChar == '&')) {
                    // 使用一个普通字符（如's'）的宽度作为其虚拟宽度
                    charWidth = this.fontRenderer.getCharWidth('s');
                } else {
                    charWidth = this.fontRenderer.getStringWidth(charToRenderWithFormat) - this.fontRenderer.getStringWidth(formatPrefix);
                }
                
                currentX += charWidth;
                i++;
            }
        }
        
        this.charXPositions.add((int)Math.round(currentX));
        GlStateManager.popMatrix();
    }
    
    public void drawCursor(int yPos, int cursorPosition) {
        int lineIndex = findLineForPosition(cursorPosition);
        if (lineIndex < 0 || lineIndex >= renderedLines.size()) return;
        
        int posInLine = cursorPosition - lineStartIndices[lineIndex];
        
        if (posInLine < charXPositions.size()) {
            int cursorX = charXPositions.get(posInLine);
            
            float scale = 1.0f;
            if (GhostConfig.NoteTaking.enableMarkdownRendering) {
                String line = renderedLines.get(lineIndex);
                if (line.startsWith("# ")) scale = 1.5f;
                else if (line.startsWith("## ")) scale = 1.2f;
            }
            float scaledFontHeight = this.fontRenderer.FONT_HEIGHT * scale;
            
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer worldrenderer = tessellator.getWorldRenderer();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.disableTexture2D();
            worldrenderer.begin(7, DefaultVertexFormats.POSITION);
            worldrenderer.pos((double)cursorX, (double)yPos - 1 + scaledFontHeight, 0.0D).endVertex();
            worldrenderer.pos((double)cursorX + 1, (double)yPos -1 + scaledFontHeight, 0.0D).endVertex();
            worldrenderer.pos((double)cursorX + 1, (double)yPos - 1, 0.0D).endVertex();
            worldrenderer.pos((double)cursorX, (double)yPos - 1, 0.0D).endVertex();
            tessellator.draw();
            GlStateManager.enableTexture2D();
        }
    }
    
    public void drawSelection(int yPos, int selectionStart, int selectionEnd, int lineIndex) {
        int lineStart = lineStartIndices[lineIndex];
        int lineEnd = (lineIndex + 1 < lineStartIndices.length) ? lineStartIndices[lineIndex+1] : Integer.MAX_VALUE;

        if (selectionEnd > lineStart && selectionStart < lineEnd) {
            int highlightStart = Math.max(selectionStart, lineStart) - lineStart;
            int highlightEnd = Math.min(selectionEnd, lineEnd) - lineStart;

            if (highlightStart < highlightEnd && highlightEnd <= charXPositions.size()) {
                int x1 = charXPositions.get(highlightStart);
                // 确保索引不越界
                int x2 = charXPositions.get(Math.min(highlightEnd, charXPositions.size() - 1));
                drawSelectionBox(x1, yPos, x2, yPos + fontRenderer.FONT_HEIGHT);
            }
        }
    }

    private void drawSelectionBox(int startX, int startY, int endX, int endY) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        GlStateManager.color(0.0F, 0.0F, 1.0F, 0.5F);
        GlStateManager.disableTexture2D();
        GlStateManager.enableColorLogic();
        GlStateManager.colorLogicOp(5387);
        worldrenderer.begin(7, DefaultVertexFormats.POSITION);
        worldrenderer.pos(startX, endY, 0.0D).endVertex();
        worldrenderer.pos(endX, endY, 0.0D).endVertex();
        worldrenderer.pos(endX, startY, 0.0D).endVertex();
        worldrenderer.pos(startX, startY, 0.0D).endVertex();
        tessellator.draw();
        GlStateManager.disableColorLogic();
        GlStateManager.enableTexture2D();
    }
    
    public int getCharIndexAt(int mouseX, int mouseY, int relativeY) {
        int lineIndex = Math.max(0, relativeY / fontRenderer.FONT_HEIGHT);
        if (lineIndex >= renderedLines.size()) return Integer.MAX_VALUE;
        
        drawStringAndCachePositions(renderedLines.get(lineIndex), textAreaX + 4, -9999, 0);
        
        int bestIndexInLine = 0;
        int minDistance = Integer.MAX_VALUE;
        for (int i = 0; i < charXPositions.size(); i++) {
            int distance = Math.abs(mouseX - charXPositions.get(i));
            if (distance < minDistance) {
                minDistance = distance;
                bestIndexInLine = i;
            }
        }
        return lineStartIndices[lineIndex] + bestIndexInLine;
    }
    
    public int findLineForPosition(int position) {
        if (lineStartIndices == null || lineStartIndices.length == 0) return 0;
        for (int i = 0; i < lineStartIndices.length - 1; i++) {
            if (position >= lineStartIndices[i] && position < lineStartIndices[i + 1]) {
                return i;
            }
        }
        return renderedLines.size() - 1;
    }
}