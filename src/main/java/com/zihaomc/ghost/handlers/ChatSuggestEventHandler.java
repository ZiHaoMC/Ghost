package com.zihaomc.ghost.handlers;

// ---- Minecraft Client 相关导入 ----
import net.minecraft.client.Minecraft; // Minecraft 主类，用于访问游戏实例
import net.minecraft.client.gui.ChatLine; // 代表聊天界面中的一行聊天记录
import net.minecraft.client.gui.GuiNewChat; // 游戏内的主聊天 GUI 类
import net.minecraft.client.gui.GuiChat; // 聊天输入界面类
import net.minecraft.client.gui.GuiTextField; // 文本输入框控件
import net.minecraft.client.gui.GuiScreen; // GUI 界面的基类

// ---- Minecraft 实用工具类导入 ----
import net.minecraft.event.ClickEvent; // 处理聊天文本点击事件
import net.minecraft.event.HoverEvent; // 处理聊天文本悬停事件
import net.minecraft.util.*; // 包含 IChatComponent, ChatComponentText, ChatStyle, EnumChatFormatting, MathHelper 等

// ---- Forge 事件系统相关导入 ----
import net.minecraftforge.event.CommandEvent; // 监听命令执行事件
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent; // 注解，标记事件处理方法
import net.minecraftforge.fml.common.gameevent.TickEvent; // 监听游戏 Tick 事件
import net.minecraftforge.client.event.ClientChatReceivedEvent; // 监听客户端接收聊天消息事件
import net.minecraftforge.client.event.GuiScreenEvent; // 监听 GUI 屏幕事件 (键盘、鼠标)
import net.minecraftforge.client.event.GuiScreenEvent.MouseInputEvent; // 监听 GUI 屏幕鼠标输入事件
import net.minecraftforge.fml.relauncher.ReflectionHelper; // FML 提供的反射工具类，用于简化访问（尤其是私有）字段和方法的操作

// ---- Java 反射与集合类导入 ----
import java.lang.reflect.Field; // 用于访问类的私有字段
import java.util.ArrayList; // 动态数组列表
import java.util.List; // 列表接口
import java.util.HashSet; // 哈希集合，用于存储不重复元素
import java.util.Set; // 集合接口
// import java.util.Arrays; // (在这个版本中未使用，可以移除或保留以备将来使用)

// ---- LWJGL 输入库导入 ----
import org.lwjgl.input.Keyboard; // 用于检测键盘按键
import org.lwjgl.input.Mouse; // 用于检测鼠标输入（滚轮）

// ---- 本项目工具类导入 ----
import com.zihaomc.ghost.LangUtil; // 语言翻译工具类
import com.zihaomc.ghost.config.GhostConfig; // Mod 配置类

/**
 * 处理与聊天建议相关的事件，包括：
 * 1. 在聊天消息（尤其是错误消息和成功命令反馈）后添加 "建议命令" 按钮。
 * 2. 实现 Shift + 箭头/滚轮 在聊天输入框中滚动发送历史。
 *
 * 使用 Forge 事件总线监听特定事件，并通过 Java 反射访问 Minecraft 的私有成员以实现功能。
 */
public class ChatSuggestEventHandler {

    // ==================
    // === 成员变量 ===
    // ==================

    // --- 状态变量 ---
    /**
     * 存储上一个由玩家成功执行的命令的完整文本。
     * 用于在 {@link #onClientTick(TickEvent.ClientTickEvent)} 中查找并处理该命令的反馈消息。
     * 在命令被处理或出错后会被设为 null。
     */
    private static String lastCommand = null;

    /**
     * 存储已处理过的聊天消息组件的哈希码 (System.identityHashCode)。
     * 用于防止对同一条聊天消息重复添加建议按钮，避免潜在的无限循环或重复渲染。
     */
    private static final Set<Integer> processedMessageHashes = new HashSet<>();

    // --- 反射字段 (用于访问 Minecraft 私有成员) ---
    // 注意：反射依赖于具体的 Minecraft 和 Forge 版本，可能在更新后失效。

    /** 反射访问 GuiNewChat 类中的 'drawnChatLines' 字段 (field_146253_i)。存储当前显示的聊天行对象列表。*/
    private static Field drawnChatLinesField = null;

    /** 反射访问 ChatLine 类中的 'chatComponent' 字段 (field_74541_b)。存储该聊天行对应的 IChatComponent 对象。*/
    private static Field chatComponentField = null;

    /**
     * 反射访问 ChatLine 类中的 'updateCounter' 字段 (field_74549_e 或 field_146250_d)。
     * 此字段用于标记消息的新旧程度，但在某些环境下可能不存在或名称不同。
     * 如果获取失败 (为 null)，将启用 Fallback V2 逻辑。
     */
    private static Field updateCounterField = null; // 尝试获取，可能失败

    /** 反射访问 GuiChat 类中的 'inputField' 字段 (field_146415_a)。代表聊天输入框的 GuiTextField 对象。*/
    private static Field chatInputField = null;

    // --- 聊天历史滚动相关状态 ---

    /** 当前在聊天历史记录中查看的索引。-1 表示未开始滚动或已返回到原始输入文本。*/
    private static int chatHistoryIndex = -1;

    /** 当用户开始使用 Shift+箭头/滚轮 滚动历史记录前，输入框中的原始文本。用于在滚动回 -1 索引时恢复。*/
    private static String originalChatText = null;

    /** 当前活动的 GuiChat 实例。用于检测 GuiChat 界面是否关闭，以便重置滚动状态。*/
    private static GuiChat activeGuiChatInstance = null;

    // ==================
    // === 构造与初始化 ===
    // ==================

    /**
     * 构造函数。
     * 创建 ChatSuggestEventHandler 实例时，会打印调试信息并尝试初始化必要的反射字段。
     */
    public ChatSuggestEventHandler() {
        System.out.println("[GhostBlock-Suggest DEBUG] ChatSuggestEventHandler 实例已创建 (处理按钮、未知命令、Shift+箭头/滚轮历史记录)。");
        initializeReflectionFields(); // 初始化反射字段
    }

    /**
     * 初始化所有需要通过反射访问的 Minecraft 私有字段。
     * 在构造函数中调用。如果任何字段查找失败，会打印错误或警告信息。
     * 字段的可用性对 Mod 的功能至关重要。
     */
    private void initializeReflectionFields() {
        // 获取 GuiNewChat.drawnChatLines (field_146253_i)
        if (drawnChatLinesField == null) {
            try {
                drawnChatLinesField = ReflectionHelper.findField(GuiNewChat.class, "field_146253_i", "drawnChatLines");
                drawnChatLinesField.setAccessible(true); // 允许访问私有字段
                System.out.println("[GhostBlock-Suggest DEBUG] 成功访问 GuiNewChat.drawnChatLines 字段。");
            } catch (ReflectionHelper.UnableToFindFieldException e) {
                System.err.println("[GhostBlock-Suggest ERROR] 无法找到 GuiNewChat.drawnChatLines 字段！建议功能可能受损。");
                // e.printStackTrace(); // 可以取消注释以获取更详细的堆栈跟踪
            }
        }

        // 获取 ChatLine.chatComponent (field_74541_b)
        if (chatComponentField == null) {
            try {
                // 尝试多个可能的名称 (混淆名, MCP名)
                chatComponentField = ReflectionHelper.findField(ChatLine.class, "field_74541_b", "chatComponent", "lineString");
                chatComponentField.setAccessible(true);
                System.out.println("[GhostBlock-Suggest DEBUG] 成功访问 ChatLine 的 IChatComponent 字段。");
            } catch (ReflectionHelper.UnableToFindFieldException e) {
                System.err.println("[GhostBlock-Suggest ERROR] 无法找到 ChatLine 的 IChatComponent 字段！建议功能可能受损。");
                // e.printStackTrace();
            }
        }

        // 尝试获取 ChatLine.updateCounter (field_74549_e 或 field_146250_d) - 这个字段不是核心必需的
        if (updateCounterField == null) {
            try {
                updateCounterField = ReflectionHelper.findField(ChatLine.class, "field_74549_e", "updateCounter", "field_146250_d");
                updateCounterField.setAccessible(true);
                System.out.println("[GhostBlock-Suggest DEBUG] 成功访问 ChatLine.updateCounter 字段。");
            } catch (ReflectionHelper.UnableToFindFieldException e) {
                // 这个字段找不到是预期的 fallback 情况，使用警告级别
                System.err.println("[GhostBlock-Suggest WARN] 无法找到 ChatLine.updateCounter 字段。将使用 Fallback V2 逻辑进行 Tick 处理。");
                updateCounterField = null; // 明确设置为 null
            }
        }

        // 获取 GuiChat.inputField (field_146415_a) - 对聊天历史滚动功能至关重要
        if (chatInputField == null) {
            try {
                chatInputField = ReflectionHelper.findField(GuiChat.class, "field_146415_a", "inputField");
                chatInputField.setAccessible(true);
                System.out.println("[GhostBlock-Suggest DEBUG] 成功访问 GuiChat.inputField 字段。");
            } catch (ReflectionHelper.UnableToFindFieldException e) {
                System.err.println("[GhostBlock-Suggest ERROR] 无法找到 GuiChat.inputField 字段！聊天历史滚动 (Shift+箭头/滚轮) 将无法工作。");
                // e.printStackTrace();
            }
        }
    }

    // ========================
    // === 事件处理方法 ===
    // ========================

    /**
     * 监听命令执行事件 ({@link CommandEvent})。
     * 当玩家尝试执行一个命令时触发。
     * 记录下完整的命令字符串到 {@code lastCommand}，以便后续在 Tick 事件中查找对应的反馈消息。
     *
     * @param event 命令事件对象，包含命令名称和参数。
     */
    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        // 检查功能是否已启用
        if (!GhostConfig.enableChatSuggestions) {
            return;
        }

        try {
            // 构建完整的命令字符串，包括 '/' 前缀和所有参数
            StringBuilder commandBuilder = new StringBuilder("/");
            commandBuilder.append(event.command.getCommandName());
            if (event.parameters != null) {
                for (String param : event.parameters) {
                    commandBuilder.append(" ").append(param);
                }
            }
            lastCommand = commandBuilder.toString(); // 记录命令
            System.out.println("[GhostBlock-Suggest] 捕获到命令执行: " + lastCommand);

            // 清理旧的处理状态，为新命令的反馈做准备
            // (虽然 Tick 事件也会清理，但这里提前清理可能有助于减少一点延迟)
            // processedMessageHashes.clear(); // 考虑是否需要在这里清理，或者只在Tick处理后清理

        } catch (Exception e) {
            System.err.println("[GhostBlock-Suggest] 捕获命令时出错: " + e.getMessage());
            lastCommand = null; // 如果在构建命令时出错，则清空记录
        }
    }

    /**
     * 监听客户端接收到聊天消息的事件 ({@link ClientChatReceivedEvent})。
     * 主要用于检测特定的系统消息，例如 "未知指令" 错误。
     * 如果检测到这类消息，并且是由玩家最近发送的命令引起的，会尝试给这条错误消息添加一个建议按钮，
     * 该按钮允许玩家点击后将之前输入的错误命令填回输入框。
     *
     * @param event 聊天消息接收事件对象，包含消息内容 (IChatComponent) 和类型。
     */
    @SubscribeEvent
    public void onChatMessageReceived(ClientChatReceivedEvent event) {
        // 检查功能是否启用，并忽略类型为 2 的消息 (通常是 Action Bar 信息)
        if (!GhostConfig.enableChatSuggestions || event.type == 2) {
            return;
        }

        IChatComponent message = event.message;
        // 使用 System.identityHashCode 作为消息实例的唯一标识符
        int messageHash = System.identityHashCode(message);

        // 如果这条消息实例已经被处理过，则跳过，防止重复添加按钮
        if (processedMessageHashes.contains(messageHash)) {
            return;
        }

        // 检查消息是否是可翻译的组件 (Minecraft 的错误和系统消息通常是这种类型)
        if (message instanceof ChatComponentTranslation) {
            ChatComponentTranslation translation = (ChatComponentTranslation) message;
            String key = translation.getKey(); // 获取消息的翻译键

            // 检查是否是 "未知指令" 或 "未找到指令" 的翻译键
            if ("commands.generic.unknownCommand".equals(key) || "commands.generic.notFound".equals(key)) {
                System.out.println("[GhostBlock-Suggest INFO ChatRcv] 检测到 '未知/未找到命令' 消息 (Key: " + key + ")");

                Minecraft mc = Minecraft.getMinecraft();
                // 基本的 null 检查，确保游戏状态正常
                if (mc.ingameGUI == null || mc.ingameGUI.getChatGUI() == null) {
                    System.err.println("[GhostBlock-Suggest ERROR ChatRcv] Minecraft GUI 或 Chat GUI 为 null！无法处理错误消息。");
                    return;
                }

                // 获取玩家已发送消息的历史记录列表
                List<String> sentMessages = mc.ingameGUI.getChatGUI().getSentMessages();

                if (sentMessages != null && !sentMessages.isEmpty()) {
                    // 获取列表中的最后一条消息，这应该是玩家刚刚尝试执行的命令
                    String lastSentMsg = sentMessages.get(sentMessages.size() - 1);
                    System.out.println("[GhostBlock-Suggest DEBUG ChatRcv] 历史记录中的最后发送消息: " + lastSentMsg);

                    // 确认最后发送的消息确实是一个命令 (以 '/' 开头)
                    if (lastSentMsg != null && lastSentMsg.startsWith("/")) {
                        // 要建议的命令就是用户输入的那个错误的命令
                        String commandToSuggest = lastSentMsg;
                        System.out.println("[GhostBlock-Suggest INFO ChatRcv] 准备建议输入的命令: " + commandToSuggest);

                        // 检查这条错误消息是否已经意外地附加了按钮 (可能由其他 Mod 或重复事件引起)
                        // 使用 Stream API 检查兄弟组件中是否已存在我们的建议按钮文本
                        boolean alreadyHasButton = message.getSiblings()
                                .stream()
                                .anyMatch(sibling -> sibling instanceof ChatComponentText &&
                                        ((ChatComponentText) sibling).getUnformattedText().contains(LangUtil.translate("ghostblock.commands.suggest.text", "\u21A9"))); // "\u21A9" 是向下的箭头符号

                        System.out.println("[GhostBlock-Suggest DEBUG ChatRcv] 错误消息是否已包含按钮？ " + alreadyHasButton);

                        if (!alreadyHasButton) {
                            System.out.println("[GhostBlock-Suggest DEBUG ChatRcv] 尝试向错误消息附加建议按钮...");
                            // 调用辅助方法来创建并附加建议按钮
                            boolean appendSuccess = appendSuggestButton(message, commandToSuggest);
                            System.out.println("[GhostBlock-Suggest DEBUG ChatRcv] appendSuggestButton 返回: " + appendSuccess);

                            if (appendSuccess) {
                                System.out.println("[GhostBlock-Suggest INFO ChatRcv] 成功将建议 ('" + commandToSuggest + "') 附加到 '" + key + "' 消息。");
                                // 标记这条消息已处理，防止在后续 Tick 或事件中再次处理
                                processedMessageHashes.add(messageHash);
                            } else {
                                System.err.println("[GhostBlock-Suggest ERROR ChatRcv] 无法将按钮附加到 '" + key + "' 消息！无论如何标记为已处理以防循环。");
                                // 即使附加失败，也标记为已处理，以避免潜在的无限尝试错误
                                processedMessageHashes.add(messageHash);
                            }
                        } else {
                            System.out.println("[GhostBlock-Suggest INFO ChatRcv] 按钮已存在于错误消息上。标记为已处理。");
                            // 如果按钮已存在，只需标记为已处理
                            processedMessageHashes.add(messageHash);
                        }
                    } else {
                        // 如果最后发送的消息不是命令，则不处理 (这通常不应该发生在这种错误场景下)
                        System.out.println("[GhostBlock-Suggest WARN ChatRcv] 最后发送的消息不是命令: " + lastSentMsg);
                    }
                } else {
                    // 如果发送历史为空，无法确定是哪个命令导致了错误
                    System.out.println("[GhostBlock-Suggest WARN ChatRcv] 已发送消息历史为空。无法确定导致错误的命令。");
                }
            }
        }
        // 对于非 ChatComponentTranslation 的消息，或者不是我们关心的错误类型，直接忽略
    }


    /**
     * 监听客户端 Tick 事件 ({@link TickEvent.ClientTickEvent})。
     * 在每个客户端 Tick 结束时执行。
     * 主要职责：
     * 1. 检查并重置聊天历史滚动状态：如果之前打开的 GuiChat 界面已关闭或切换，则重置滚动索引和原始文本。
     * 2. 处理 {@code lastCommand} 的反馈：如果 {@code lastCommand} 不为 null（意味着有一个待处理的成功命令），
     *    则扫描聊天行，找到该命令产生的反馈消息块，并在其末尾添加建议按钮。
     *    使用 Fallback V2 逻辑，不依赖于可能不存在的 `updateCounter` 字段。
     *
     * @param event Tick 事件对象。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // 仅在 Tick 结束阶段执行逻辑，避免重复执行或在不合适的时间执行
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        // --- 1. 检查并重置聊天历史滚动状态 ---
        // 如果我们正在跟踪一个 GuiChat 实例 (activeGuiChatInstance != null)
        // 并且当前屏幕不再是那个实例 (mc.currentScreen != activeGuiChatInstance)
        // 说明玩家关闭了聊天界面或切换到了其他 GUI
        if (activeGuiChatInstance != null && mc.currentScreen != activeGuiChatInstance) {
            System.out.println("[GhostBlock-Suggest DEBUG Scroll Tick] 检测到跟踪的 GuiChat 关闭或改变。重置滚动状态。");
            activeGuiChatInstance = null; // 清除对旧 GuiChat 实例的跟踪
            chatHistoryIndex = -1;        // 重置历史记录索引
            originalChatText = null;      // 清除保存的原始文本
        }

        // --- 2. 处理聊天建议按钮的逻辑 (针对 lastCommand 的反馈) ---
        // 检查功能是否已启用
        if (!GhostConfig.enableChatSuggestions) {
            // 如果功能被禁用，确保清除任何待处理的命令
            if (lastCommand != null) {
                lastCommand = null;
            }
            return;
        }

        // 如果没有待处理的命令，则无需继续执行按钮添加逻辑
        if (lastCommand == null) {
            return;
        }

        // 确保 Minecraft GUI 和必要的反射字段已准备就绪
        if (mc.ingameGUI == null || mc.ingameGUI.getChatGUI() == null ||
            drawnChatLinesField == null || chatComponentField == null) {
            // 如果缺少关键组件或字段，无法处理，打印警告并可能清除 lastCommand
            System.err.println("[GhostBlock-Suggest WARN Tick] GUI 或必需的反射字段为 null。无法处理命令反馈: " + lastCommand);
            // lastCommand = null; // 考虑是否在此处清除 lastCommand，防止不断尝试
            return;
        }

        // 调用 Fallback V2 逻辑来查找并处理 lastCommand 的反馈消息
        processTickFallbackV2(mc, lastCommand);
    }

    /**
     * Tick 处理的 Fallback V2 实现。
     * 该方法不依赖 ChatLine 的 `updateCounter` 字段。它通过迭代聊天行，
     * 并根据消息是否已被处理 (检查 {@code processedMessageHashes}) 来识别属于同一命令反馈的 "消息块"。
     * 它会找到未处理消息块的最下面一行，并尝试附加建议按钮。
     *
     * @param mc Minecraft 实例。
     * @param commandToProcess 当前需要处理反馈的命令字符串 (即 {@code lastCommand})。
     */
    private void processTickFallbackV2(Minecraft mc, String commandToProcess) {
        GuiNewChat chatGUI = mc.ingameGUI.getChatGUI();
        try {
            // 通过反射获取当前显示的聊天行列表
            @SuppressWarnings("unchecked") // 类型转换是基于反射获取的，需要抑制警告
            final List<ChatLine> drawnChatLines = (List<ChatLine>) drawnChatLinesField.get(chatGUI);

            if (drawnChatLines == null || drawnChatLines.isEmpty()) {
                return; // 如果没有聊天行，直接返回
            }

            boolean commandProcessedThisTick = false; // 标记本次 Tick 是否成功处理了命令
            // 临时的 Set，用于收集本次找到的消息块的所有哈希码，成功处理后再统一添加到全局 Set
            Set<Integer> hashesToMarkProcessedLocally = new HashSet<>();

            // 从最新的聊天行开始向前（向上）迭代
            for (int i = drawnChatLines.size() - 1; i >= 0; i--) {
                ChatLine currentChatLine = drawnChatLines.get(i);
                IChatComponent currentComponent = (IChatComponent) chatComponentField.get(currentChatLine);
                int currentComponentHash = System.identityHashCode(currentComponent);

                // 如果当前行已经被处理过，则跳过
                if (processedMessageHashes.contains(currentComponentHash)) {
                    continue;
                }

                // 如果当前行是已知的错误消息，也跳过（错误消息由 onChatMessageReceived 处理）
                if (currentComponent instanceof ChatComponentTranslation) {
                    String key = ((ChatComponentTranslation) currentComponent).getKey();
                    if ("commands.generic.unknownCommand".equals(key) || "commands.generic.notFound".equals(key)) {
                        continue;
                    }
                }

                // --- 找到消息块的底部 ---
                // 假设当前行 (i) 是消息块的顶部 (最新消息)
                // 我们需要找到这个连续未处理块的最下面一行 (索引最小)
                ChatLine targetBottomLine = currentChatLine; // 初始假设底部就是当前行
                int bottomIndex = i;

                // 向前 (向上，索引减小) 查找连续的、未被处理的、非错误的行
                for (int j = i - 1; j >= 0; j--) {
                    ChatLine previousLine = drawnChatLines.get(j);
                    IChatComponent previousComponent = (IChatComponent) chatComponentField.get(previousLine);
                    int previousHash = System.identityHashCode(previousComponent);

                    // 检查前一行是否是错误消息
                    boolean prevIsError = false;
                    if (previousComponent instanceof ChatComponentTranslation) {
                        String prevKey = ((ChatComponentTranslation) previousComponent).getKey();
                        prevIsError = "commands.generic.unknownCommand".equals(prevKey) || "commands.generic.notFound".equals(prevKey);
                    }

                    // 如果前一行已经被处理过，或者是错误消息，说明消息块在这里中断了
                    if (processedMessageHashes.contains(previousHash) || prevIsError) {
                        break; // 停止向前查找，当前的 bottomIndex 和 targetBottomLine 就是块的底部
                    } else {
                        // 否则，将前一行设为新的潜在底部，并更新索引
                        targetBottomLine = previousLine;
                        bottomIndex = j;
                    }
                }

                // --- Fallback V2 的边界情况处理 ---
                // 如果找到的底部是列表的第一行 (index 0)，需要额外检查这一行是否本身就是已处理或错误行。
                // 如果是，并且列表不止一行，那么真正的底部应该是第二行 (index 1)。
                // 这是为了防止将按钮错误地附加到已被处理的第一行上。
                if (bottomIndex == 0) {
                    try {
                        IChatComponent firstComp = (IChatComponent) chatComponentField.get(drawnChatLines.get(0));
                        int firstHash = System.identityHashCode(firstComp);
                        boolean firstIsError = (firstComp instanceof ChatComponentTranslation &&
                                ("commands.generic.unknownCommand".equals(((ChatComponentTranslation) firstComp).getKey()) ||
                                 "commands.generic.notFound".equals(((ChatComponentTranslation) firstComp).getKey())));

                        // 条件：第一行已处理或为错误，且列表有多于一行，且块的顶部不是第一行自己 (i > 0)
                        if ((processedMessageHashes.contains(firstHash) || firstIsError) && i > 0 && drawnChatLines.size() > 1) {
                           bottomIndex = 1; // 将底部索引修正为 1
                           targetBottomLine = drawnChatLines.get(1); // 更新底部行为第二行
                           System.out.println("[GhostBlock-Suggest DEBUG Tick - FBV2] 边缘情况命中：底部为索引 0 (已处理/错误)，已移至索引 1。");
                        }
                    } catch (Exception reflectionError) {
                        // 反射错误，忽略这个边界检查
                        System.err.println("[GhostBlock-Suggest WARN Tick - FBV2] 检查索引 0 的边缘情况时发生反射错误。");
                    }
                }


                // --- 附加按钮 ---
                // 获取最终确定的底部行的组件
                IChatComponent bottomComponent = (IChatComponent) chatComponentField.get(targetBottomLine);

                // 再次检查目标底部行是否已经有按钮了（以防万一）
                boolean alreadyHasButton = bottomComponent.getSiblings()
                        .stream()
                        .anyMatch(s -> s instanceof ChatComponentText &&
                                ((ChatComponentText) s).getUnformattedText().contains(LangUtil.translate("ghostblock.commands.suggest.text", "\u21A9")));

                if (!alreadyHasButton) {
                    // 如果没有按钮，尝试附加
                    boolean appendSuccess = appendSuggestButton(bottomComponent, commandToProcess); // 建议原始的、成功执行的命令

                    if (appendSuccess) {
                        System.out.println("[GhostBlock-Suggest INFO Tick - FALLBACK V2] 成功为 '" + commandToProcess + "' 反馈附加建议按钮 (底部索引: " + bottomIndex + ")。");

                        // 成功附加按钮后，将这个消息块的所有行都标记为已处理
                        hashesToMarkProcessedLocally.clear(); // 清空临时 set
                        for (int k = i; k >= bottomIndex; k--) { // 从块顶到块底
                            try {
                                hashesToMarkProcessedLocally.add(System.identityHashCode(chatComponentField.get(drawnChatLines.get(k))));
                            } catch (Exception refErr) {
                                System.err.println("[GhostBlock-Suggest WARN Tick - FBV2] 获取哈希码以标记处理索引 " + k + " 时出错");
                            }
                        }
                        processedMessageHashes.addAll(hashesToMarkProcessedLocally); // 将本地收集的哈希码添加到全局 Set
                        commandProcessedThisTick = true; // 标记命令已处理
                        break; // 找到并处理了一个块，可以退出本次 Tick 的循环

                    } else {
                        System.err.println("[GhostBlock-Suggest ERROR Tick - FALLBACK V2] 无法为命令 '" + commandToProcess + "' 附加按钮。将块标记为已处理以避免循环 (索引 " + bottomIndex + " 到 " + i + ")。");
                        // 附加失败，但仍然标记这个块为已处理，防止无限次尝试失败
                        hashesToMarkProcessedLocally.clear();
                        for (int k = i; k >= bottomIndex; k--) {
                            try {
                                hashesToMarkProcessedLocally.add(System.identityHashCode(chatComponentField.get(drawnChatLines.get(k))));
                            } catch (Exception refErr) {
                                // 忽略这里的错误
                            }
                        }
                        processedMessageHashes.addAll(hashesToMarkProcessedLocally);
                        // 不设置 commandProcessedThisTick = true，因为处理未成功
                        break; // 退出循环，避免在同一 Tick 中处理其他潜在块（如果有的话）
                    }
                } else {
                    // 如果目标底部行已经有按钮，说明这个块可能在之前的 Tick 或事件中被处理了
                    System.out.println("[GhostBlock-Suggest DEBUG Tick - FALLBACK V2] 按钮已存在于目标底部行 (索引 " + bottomIndex + ")。将块标记为已处理 (索引 " + bottomIndex + " 到 " + i + ")。");
                    // 将这个块标记为已处理
                    hashesToMarkProcessedLocally.clear();
                    for (int k = i; k >= bottomIndex; k--) {
                        try {
                            hashesToMarkProcessedLocally.add(System.identityHashCode(chatComponentField.get(drawnChatLines.get(k))));
                        } catch (Exception refErr) {
                            // 忽略这里的错误
                        }
                    }
                    processedMessageHashes.addAll(hashesToMarkProcessedLocally);
                    // 这里不需要 break，因为我们可能需要继续向上查找更早的、未处理的命令反馈块
                    // （虽然理论上一个命令只有一个反馈块，但以防万一）
                }
            } // end for loop iterating through drawnChatLines

            // 如果本次 Tick 成功处理了命令的反馈，则清除 lastCommand
            if (commandProcessedThisTick) {
                lastCommand = null;
                System.out.println("[GhostBlock-Suggest DEBUG Tick - FALLBACK V2] 命令反馈已处理，清除 lastCommand。");
            } else {
                // 如果循环结束仍未处理命令 (可能是还没收到反馈，或者所有收到的都被标记了)
                // 可以考虑添加一个超时或尝试次数限制来最终清除 lastCommand，防止它永远存在
                // System.out.println("[GhostBlock-Suggest DEBUG Tick - FBV2] 本次 Tick 未找到 " + commandToProcess + " 的未处理反馈。");
            }

        } catch (Exception e) {
            System.err.println("[GhostBlock-Suggest ERROR] Fallback V2 Tick 处理期间发生异常:");
            e.printStackTrace();
            lastCommand = null; // 发生未知错误时，清除 lastCommand 以避免潜在问题
        }
    }

    /**
     * 监听 GUI 界面的键盘输入事件 ({@link GuiScreenEvent.KeyboardInputEvent.Pre})。
     * 在键盘按键被处理之前触发。
     * 用于在 {@link GuiChat} 界面中实现 Shift + 上/下箭头键 来滚动浏览已发送的命令历史。
     *
     * @param event 键盘输入事件对象。
     */
    @SubscribeEvent
    public void onGuiKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        // 核心修改：检查新的配置项
        if (!GhostConfig.enableCommandHistoryScroll || chatInputField == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        // 确认当前打开的 GUI 是聊天输入界面 (GuiChat)
        if (!(event.gui instanceof GuiChat)) {
            // 如果不是 GuiChat，重置与 GuiChat 相关的状态（以防万一）
            if (activeGuiChatInstance != null) {
                 System.out.println("[GhostBlock-Suggest DEBUG Kbd] 当前屏幕不是 GuiChat。重置滚动状态。");
                 activeGuiChatInstance = null;
                 chatHistoryIndex = -1;
                 originalChatText = null;
            }
            return;
        }

        GuiChat currentChatGui = (GuiChat) event.gui;

        // 更新当前活动的 GuiChat 实例引用，用于 Tick 事件中的状态重置
        if (activeGuiChatInstance == null || activeGuiChatInstance != currentChatGui) {
            activeGuiChatInstance = currentChatGui;
            // 重置滚动状态，因为我们进入了一个新的 (或重新进入) GuiChat 实例
            chatHistoryIndex = -1;
            originalChatText = null;
            System.out.println("[GhostBlock-Suggest DEBUG Kbd] GuiChat 实例已更新/进入: " + currentChatGui + ". 滚动状态已重置。");
        }

        // 检查是否是按键按下的事件 (而不是抬起)
        if (Keyboard.getEventKeyState()) {
            int keyCode = Keyboard.getEventKey(); // 获取按下的键码
            boolean shiftDown = GuiScreen.isShiftKeyDown(); // 检查 Shift 键是否被按下

            // 只在 Shift 键被按下的情况下处理
            if (shiftDown) {
                boolean scrollUp = (keyCode == Keyboard.KEY_UP);   // 上箭头
                boolean scrollDown = (keyCode == Keyboard.KEY_DOWN); // 下箭头

                // 如果是 Shift + 上箭头 或 Shift + 下箭头
                if (scrollUp || scrollDown) {
                    System.out.println("[GhostBlock-Suggest DEBUG Kbd] 检测到 Shift + " + (scrollUp ? "上" : "下") + " 箭头");

                    GuiTextField inputFieldInstance;
                    try {
                        // 通过反射获取当前 GuiChat 的输入框实例
                        inputFieldInstance = (GuiTextField) chatInputField.get(currentChatGui);
                        if (inputFieldInstance == null) {
                             System.err.println("[GhostBlock-Suggest ERROR Kbd] 输入框实例为 null！");
                             return;
                        }
                    } catch (Exception e) {
                        System.err.println("[GhostBlock-Suggest ERROR Kbd] 通过反射获取输入框实例失败！");
                        e.printStackTrace();
                        return;
                    }

                    // 获取已发送消息的历史记录
                    List<String> sentMessages = mc.ingameGUI.getChatGUI().getSentMessages();
                    // 如果历史记录为空，创建一个空列表以避免 NullPointerException
                    if (sentMessages == null) {
                        sentMessages = new ArrayList<>();
                    }

                    // 如果是第一次滚动 (index 为 -1)，保存当前输入框中的文本
                    if (chatHistoryIndex == -1) {
                        originalChatText = inputFieldInstance.getText();
                        System.out.println("[GhostBlock-Suggest DEBUG Kbd] 已保存原始聊天文本: \"" + originalChatText + "\"");
                    }

                    // 根据滚动方向调整历史记录索引
                    if (scrollUp) {
                        // 向上滚动：如果是第一次滚动，跳到最后一条历史；否则索引减 1
                        chatHistoryIndex = (chatHistoryIndex == -1) ? sentMessages.size() - 1 : chatHistoryIndex - 1;
                    } else { // scrollDown
                        // 向下滚动：如果已经在原始文本状态 (-1)，保持不变；否则索引加 1
                        chatHistoryIndex = (chatHistoryIndex == -1) ? -1 : chatHistoryIndex + 1;
                    }

                    // 使用 MathHelper.clamp_int 确保索引在有效范围内 [-1, historySize - 1]
                    // -1 代表恢复到 originalChatText
                    chatHistoryIndex = MathHelper.clamp_int(chatHistoryIndex, -1, sentMessages.size() - 1);

                    // 根据新的索引确定要设置的文本
                    String newText;
                    if (chatHistoryIndex >= 0 && chatHistoryIndex < sentMessages.size()) {
                        // 如果索引有效，从历史记录中获取文本
                        newText = sentMessages.get(chatHistoryIndex);
                    } else {
                        // 如果索引是 -1 (或无效)，恢复到原始文本 (如果原始文本为 null 则设置为空字符串)
                        newText = (originalChatText != null) ? originalChatText : "";
                        chatHistoryIndex = -1; // 确保索引是 -1
                    }

                    // 更新输入框的文本并将光标移动到末尾
                    inputFieldInstance.setText(newText);
                    inputFieldInstance.setCursorPositionEnd(); // 将光标移动到文本末尾

                    // 取消原始的键盘事件，防止 Minecraft 默认的聊天历史滚动行为 (通常是无 Shift 的上下箭头)
                    event.setCanceled(true);
                    System.out.println("[GhostBlock-Suggest DEBUG Kbd] 历史记录滚动已处理。新索引: " + chatHistoryIndex + ", 文本已设置为: \"" + newText + "\"");
                }
            }
        }
    }

     /**
     * 监听 GUI 界面的鼠标输入事件 ({@link MouseInputEvent.Pre})。
     * 在鼠标事件（包括滚轮）被处理之前触发。
     * 用于在 {@link GuiChat} 界面中实现 Shift + 鼠标滚轮 来滚动浏览已发送的命令历史。
     *
     * @param event 鼠标输入事件对象。
     */
    @SubscribeEvent
    public void onGuiMouseInput(MouseInputEvent.Pre event) {
        // 核心修改：检查新的配置项
        if (!GhostConfig.enableCommandHistoryScroll || chatInputField == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        // 确认当前打开的 GUI 是聊天输入界面 (GuiChat)
        if (!(event.gui instanceof GuiChat)) {
            // 不在 GuiChat 中，无需处理，也不必重置状态（键盘事件会处理）
            return;
        }

        // 检查是否是滚轮事件 (getEventButton() == -1)
        // 并且滚轮有滚动量 (getEventDWheel() != 0)
        // 并且 Shift 键被按下 (GuiScreen.isShiftKeyDown())
        if (Mouse.getEventButton() == -1 && Mouse.getEventDWheel() != 0 && GuiScreen.isShiftKeyDown()) {
            int delta = Mouse.getEventDWheel(); // 获取滚轮滚动量 (正数向上，负数向下)
            boolean scrollUp = delta > 0; // 向上滚动

            System.out.println("[GhostBlock-Suggest DEBUG Mouse] 检测到 Shift + 鼠标滚轮: " + (scrollUp ? "上" : "下"));

            GuiChat currentChatGui = (GuiChat) event.gui;
            GuiTextField inputFieldInstance;
            try {
                // 通过反射获取当前 GuiChat 的输入框实例
                inputFieldInstance = (GuiTextField) chatInputField.get(currentChatGui);
                 if (inputFieldInstance == null) {
                     System.err.println("[GhostBlock-Suggest ERROR Mouse] 输入框实例为 null！");
                     return;
                }
            } catch (Exception e) {
                System.err.println("[GhostBlock-Suggest ERROR Mouse] 通过反射获取输入框实例失败！");
                e.printStackTrace();
                return;
            }

            // 获取已发送消息的历史记录
            List<String> sentMessages = mc.ingameGUI.getChatGUI().getSentMessages();
            // 如果历史记录为空或 null，则无法滚动，直接返回
            if (sentMessages == null || sentMessages.isEmpty()) {
                 System.out.println("[GhostBlock-Suggest DEBUG Mouse] 已发送消息历史为空。无法滚动。");
                return;
            }

             // 逻辑与键盘滚动类似：
             // 如果是第一次滚动 (index 为 -1)，保存当前输入框中的文本
            if (chatHistoryIndex == -1) {
                originalChatText = inputFieldInstance.getText();
                System.out.println("[GhostBlock-Suggest DEBUG Mouse] 已保存原始聊天文本: \"" + originalChatText + "\"");
            }

            // 根据滚动方向调整历史记录索引
            if (scrollUp) {
                chatHistoryIndex = (chatHistoryIndex == -1) ? sentMessages.size() - 1 : chatHistoryIndex - 1;
            } else { // scrollDown (delta < 0)
                chatHistoryIndex = (chatHistoryIndex == -1) ? -1 : chatHistoryIndex + 1;
            }

            // 限制索引范围
            chatHistoryIndex = MathHelper.clamp_int(chatHistoryIndex, -1, sentMessages.size() - 1);

            // 设置文本
            String newText;
            if (chatHistoryIndex >= 0) { // 索引 >= 0 意味着在历史记录中
                newText = sentMessages.get(chatHistoryIndex);
            } else { // 索引 = -1，恢复原始文本
                newText = (originalChatText != null) ? originalChatText : "";
                chatHistoryIndex = -1; // 确保索引是 -1
            }

            inputFieldInstance.setText(newText);
            inputFieldInstance.setCursorPositionEnd();

            // 取消原始的鼠标滚轮事件，防止它影响聊天界面的滚动或其他行为
            event.setCanceled(true);
            System.out.println("[GhostBlock-Suggest DEBUG Mouse] 历史记录滚动已处理。新索引: " + chatHistoryIndex + ", 文本已设置为: \"" + newText + "\"");
        }
    }


    // ====================
    // === 辅助方法 ===
    // ====================

    /**
     * 创建一个包含建议命令功能的按钮组件，并将其附加到目标聊天组件的末尾。
     * 按钮外观为 "[↩]" (或其他翻译文本)，颜色为水蓝色，加粗。
     * 点击按钮会将 {@code commandToSuggest} 填入聊天输入框。
     * 悬停在按钮上会显示提示信息，包含要建议的命令。
     *
     * @param targetComponent  要将按钮附加到的目标 IChatComponent。
     * @param commandToSuggest 点击按钮时要建议的命令字符串 (通常是完整的命令，包括 '/')。
     * @return 如果成功创建并附加按钮，返回 {@code true}；否则返回 {@code false} (例如参数为空或发生异常)。
     */
    private boolean appendSuggestButton(IChatComponent targetComponent, String commandToSuggest) {
        // 基本的参数校验
        if (targetComponent == null || commandToSuggest == null) {
            System.err.println("[GhostBlock-Suggest ERROR Append] 目标组件或要建议的命令为 null！");
            return false;
        }

        try {
            // --- 创建按钮文本 ---
            // 从 LangUtil 获取按钮的显示文本，默认是 " ↩" (带前导空格)
            String suggestText = LangUtil.translate("ghostblock.commands.suggest.text", " \u21A9"); // U+21A9: Leftwards Arrow with Hook
            ChatComponentText suggestComponent = new ChatComponentText(suggestText);

            // --- 创建交互事件 ---
            // 1. 点击事件 (ClickEvent): 类型为 SUGGEST_COMMAND，值为要建议的命令
            ClickEvent suggestClickEvent = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, commandToSuggest);

            // 2. 悬停事件 (HoverEvent): 类型为 SHOW_TEXT
            //    悬停时显示的文本是 "建议: <命令>" (或其他翻译)，灰色
            String hoverTextKey = "ghostblock.commands.suggest.hovertext"; // 翻译键
            String hoverTextRaw = LangUtil.translate(hoverTextKey, commandToSuggest); // 获取翻译后的悬停文本
            IChatComponent hoverComponent = new ChatComponentText(hoverTextRaw)
                    .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY)); // 设置为灰色
            HoverEvent suggestHoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent);

            // --- 创建并应用样式 ---
            ChatStyle buttonStyle = new ChatStyle()
                    .setColor(EnumChatFormatting.AQUA)   // 设置颜色为水蓝
                    .setBold(true)                       // 设置为粗体
                    .setChatClickEvent(suggestClickEvent) // 应用点击事件
                    .setChatHoverEvent(suggestHoverEvent); // 应用悬停事件
            suggestComponent.setChatStyle(buttonStyle); // 将样式应用到按钮文本组件

            // --- 附加按钮 ---
            // 将创建好的按钮组件作为兄弟节点附加到目标组件后面
            targetComponent.appendSibling(suggestComponent);

            return true; // 成功

        } catch (Exception e) {
            // 捕获任何在创建或附加过程中可能发生的异常
            String targetTextSnippet = "N/A";
            try {
                // 尝试获取目标组件的文本片段用于日志记录
                targetTextSnippet = targetComponent.getUnformattedText().substring(0, Math.min(50, targetComponent.getUnformattedText().length()));
            } catch (Exception ignored) {
                // 忽略获取文本时可能发生的错误
            }

            System.err.println("[GhostBlock-Suggest ERROR Append] 无法将建议按钮附加到组件: \"" + targetTextSnippet + "...\"");
            e.printStackTrace();
            return false; // 失败
        }
    }
}
