package com.zihaomc.ghost.features.notes;

import com.zihaomc.ghost.LangUtil;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 笔记功能的帮助界面，用于显示 Markdown 和颜色代码的语法。
 */
public class GuiNoteHelp extends GuiScreen {

    private final GuiScreen parentScreen;
    private List<String> helpLines;

    private int scrollOffset = 0;
    private int maxScroll = 0;

    public GuiNoteHelp(GuiScreen parent) {
        this.parentScreen = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        // 将按钮文字从“完成”改为“返回”
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height - 28, LangUtil.translate("ghost.gui.note.help.back")));
        
        populateHelpLines();
    }

    /**
     * 从语言文件加载并格式化所有帮助文本行。
     */
    private void populateHelpLines() {
        this.helpLines = new ArrayList<>();
        int wrapWidth = this.width - 50;

        helpLines.add(EnumChatFormatting.GOLD + LangUtil.translate("ghost.gui.note.help.title"));
        helpLines.add(""); // 空行

        // Markdown 部分
        helpLines.add(EnumChatFormatting.AQUA + LangUtil.translate("ghost.gui.note.help.markdown_header"));
        helpLines.addAll(this.fontRendererObj.listFormattedStringToWidth(LangUtil.translate("ghost.gui.note.help.headings.title") + " " + LangUtil.translate("ghost.gui.note.help.headings.desc"), wrapWidth));
        helpLines.addAll(this.fontRendererObj.listFormattedStringToWidth(LangUtil.translate("ghost.gui.note.help.bold.title") + " " + LangUtil.translate("ghost.gui.note.help.bold.desc"), wrapWidth));
        helpLines.addAll(this.fontRendererObj.listFormattedStringToWidth(LangUtil.translate("ghost.gui.note.help.italic.title") + " " + LangUtil.translate("ghost.gui.note.help.italic.desc"), wrapWidth));
        helpLines.addAll(this.fontRendererObj.listFormattedStringToWidth(LangUtil.translate("ghost.gui.note.help.strike.title") + " " + LangUtil.translate("ghost.gui.note.help.strike.desc"), wrapWidth));
        helpLines.addAll(this.fontRendererObj.listFormattedStringToWidth(LangUtil.translate("ghost.gui.note.help.list.title") + " " + LangUtil.translate("ghost.gui.note.help.list.desc"), wrapWidth));
        helpLines.add("");

        // 颜色代码部分
        helpLines.add(EnumChatFormatting.AQUA + LangUtil.translate("ghost.gui.note.help.color_header"));
        helpLines.addAll(this.fontRendererObj.listFormattedStringToWidth(LangUtil.translate("ghost.gui.note.help.color_desc"), wrapWidth));
        helpLines.add(LangUtil.translate("ghost.gui.note.help.color_example"));
        helpLines.add("§00 §11 §22 §33 §44 §55 §66 §77");
        helpLines.add("§88 §99 §aa §bb §cc §dd §ee §ff");
        helpLines.add("");

        // 格式代码部分
        helpLines.add(EnumChatFormatting.AQUA + LangUtil.translate("ghost.gui.note.help.format_header"));
        helpLines.addAll(this.fontRendererObj.listFormattedStringToWidth(LangUtil.translate("ghost.gui.note.help.format_desc"), wrapWidth));
        helpLines.add("§l" + LangUtil.translate("ghost.gui.note.help.format.bold") + " (§l&l§r), " + "§o" + LangUtil.translate("ghost.gui.note.help.format.italic") + " (§o&o§r)");
        helpLines.add("§n" + LangUtil.translate("ghost.gui.note.help.format.underline") + " (§n&n§r), " + "§m" + LangUtil.translate("ghost.gui.note.help.format.strike") + " (§m&m§r)");
        helpLines.add(LangUtil.translate("ghost.gui.note.help.format.reset") + " (§r&r§r)");
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    /**
     * 覆写键盘输入方法，以自定义 ESC 键的行为。
     */
    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 当按下 ESC 键 (keyCode 为 1) 时，返回到父界面 (笔记编辑器)
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(this.parentScreen);
        }
        // 对于其他按键，不执行任何操作，因为这是一个只读界面
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0 && this.maxScroll > 0) {
            int scrollAmount = this.fontRendererObj.FONT_HEIGHT * 3 * (dWheel < 0 ? 1 : -1);
            this.scrollOffset = Math.max(0, Math.min(this.maxScroll, this.scrollOffset + scrollAmount));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        
        int yPos = 35 - this.scrollOffset;
        int xPos = 25;

        // 计算可滚动范围
        this.maxScroll = Math.max(0, (this.helpLines.size() + 2) * this.fontRendererObj.FONT_HEIGHT - this.height + 60);
        this.scrollOffset = Math.min(this.maxScroll, Math.max(0, this.scrollOffset));

        for (String line : this.helpLines) {
            this.fontRendererObj.drawStringWithShadow(line, xPos, yPos, 0xFFFFFF);
            yPos += this.fontRendererObj.FONT_HEIGHT;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}