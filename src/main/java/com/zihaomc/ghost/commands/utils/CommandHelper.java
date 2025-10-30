package com.zihaomc.ghost.commands.utils;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState.BlockStateProxy;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 包含 GhostBlockCommand 及其处理器所需的各种静态辅助方法。
 * 例如：消息格式化、参数解析、文件操作、Tab补全建议等。
 */
public class CommandHelper {

    // --- 颜色常量 ---
    public static final EnumChatFormatting LABEL_COLOR = EnumChatFormatting.GRAY;
    public static final EnumChatFormatting VALUE_COLOR = EnumChatFormatting.YELLOW;
    public static final EnumChatFormatting FINISH_COLOR = EnumChatFormatting.GREEN;

    /**
     * 格式化带[Ghost]前缀的消息（默认灰色）。
     * @param messageKey 语言文件键
     * @param args 格式化参数
     * @return 格式化后的 ChatComponentText
     */
    public static ChatComponentText formatMessage(String messageKey, Object... args) {
        return formatMessage(EnumChatFormatting.GRAY, messageKey, args);
    }

    /**
     * 格式化带[Ghost]前缀的消息（带指定颜色）。
     * @param contentColor 内容文本颜色
     * @param messageKey 语言文件键
     * @param args 格式化参数
     * @return 格式化后的 ChatComponentText
     */
    public static ChatComponentText formatMessage(EnumChatFormatting contentColor, String messageKey, Object... args) {
        ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
        prefix.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));
        ChatComponentText content = new ChatComponentText(LangUtil.translate(messageKey, args));
        content.setChatStyle(new ChatStyle().setColor(contentColor));
        prefix.appendSibling(content);
        return prefix;
    }

    /**
     * 解析方块状态字符串 (例如 "minecraft:stone:1" 或 "log")。
     * @param input 输入字符串
     * @return BlockStateProxy 实例
     * @throws CommandException 如果解析失败
     */
    public static BlockStateProxy parseBlockState(String input) throws CommandException {
        try {
            Block block;
            int meta = 0;
            try {
                block = CommandBase.getBlockByText(Minecraft.getMinecraft().thePlayer, input);
            } catch (NumberFormatException nfe) {
                try {
                    int blockId = Integer.parseInt(input);
                    block = Block.getBlockById(blockId);
                    if (block == null) {
                        throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
                    }
                } catch (NumberFormatException nfe2) {
                    throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
                }
            }
            if (input.contains(":")) {
                String[] parts = input.split(":");
                String potentialMetaStr = parts[parts.length - 1];
                if (isNumber(potentialMetaStr)) {
                    String nameWithoutMeta = input.substring(0, input.lastIndexOf(':'));
                    try {
                        Block blockFromNameOnly = CommandBase.getBlockByText(Minecraft.getMinecraft().thePlayer, nameWithoutMeta);
                        if (blockFromNameOnly.equals(block)) {
                            try {
                                int parsedMeta = Integer.parseInt(potentialMetaStr);
                                block.getStateFromMeta(parsedMeta);
                                meta = parsedMeta;
                            } catch (IllegalArgumentException e) {
                                LogUtil.warn("log.warn.command.set.meta.invalid", block.getRegistryName(), potentialMetaStr);
                            }
                        }
                    } catch (CommandException e) { /* 名称部分无效时忽略 */ }
                }
            }
            if (block == Blocks.air) meta = 0;
            LogUtil.debug("log.info.command.parse.success", block.getRegistryName(), Block.getIdFromBlock(block), meta);
            return new BlockStateProxy(Block.getIdFromBlock(block), meta);
        } catch (CommandException ce) {
            throw ce;
        } catch (Exception e) {
            LogUtil.printStackTrace("log.error.command.parse.failed", e, input);
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
        }
    }
    
    /**
     * 解析命令参数中的坐标，支持相对坐标(~)。
     * @param sender 命令发送者
     * @param args   命令参数数组
     * @param index  坐标参数的起始索引
     * @return 解析后的 BlockPos
     * @throws CommandException 如果坐标无效
     */
    public static BlockPos parseBlockPosLegacy(ICommandSender sender, String[] args, int index) throws CommandException {
        if (args.length < index + 3) {
            throw new WrongUsageException("ghostblock.commands.usage");
        }
        EntityPlayer player = sender instanceof EntityPlayer ? (EntityPlayer) sender : null;
        double baseX = (player != null) ? player.posX : 0.0D;
        double baseY = (player != null) ? player.posY : 0.0D;
        double baseZ = (player != null) ? player.posZ : 0.0D;
        double x = parseRelativeCoordinateLegacy(sender, args[index], baseX);
        double y = parseRelativeCoordinateLegacy(sender, args[index + 1], baseY);
        double z = parseRelativeCoordinateLegacy(sender, args[index + 2], baseZ);
        return new BlockPos(Math.floor(x), Math.floor(y), Math.floor(z));
    }

    /**
     * 解析单个相对或绝对坐标值。
     * @param sender 命令发送者
     * @param input  坐标字符串 (如 "10", "~", "~-5")
     * @param base   相对坐标的基准值
     * @return 解析后的 double 型坐标值
     * @throws CommandException 如果输入无效
     */
    private static double parseRelativeCoordinateLegacy(ICommandSender sender, String input, double base) throws CommandException {
        if (input.startsWith("~")) {
            String offsetStr = input.substring(1);
            if (offsetStr.isEmpty()) {
                return base;
            } else {
                try {
                    return base + Double.parseDouble(offsetStr);
                } catch (NumberFormatException e) {
                    throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_coordinate_format", input));
                }
            }
        } else {
            try {
                return Double.parseDouble(input);
            } catch (NumberFormatException e) {
                throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_coordinate_format", input));
            }
        }
    }

    /**
     * 检查字符串是否可以解析为整数。
     * @param input 待检查字符串
     * @return 如果是数字则为 true
     */
    public static boolean isNumber(String input) {
        if (input == null || input.isEmpty()) return false;
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 验证批次大小是否为正数。
     * @param batchSize 批次大小
     * @throws CommandException 如果无效
     */
    public static void validateBatchSize(int batchSize) throws CommandException {
        if (batchSize <= 0) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.batch_size_too_small"));
        }
    }

    /**
     * 获取当前世界/维度的自动清除文件名。
     * @param world 客户端世界
     * @return 文件名字符串 (不含后缀)
     */
    public static String getAutoClearFileName(WorldClient world) {
        return "clear_" + GhostBlockData.getWorldIdentifier(world);
    }
    
    /**
     * 获取自动放置功能专用的保存文件名。
     * @param world 客户端世界
     * @return 文件名字符串 (不含后缀)
     */
    public static String getAutoPlaceSaveFileName(net.minecraft.world.World world) {
        if (world == null) return "autoplace_unknown_world";
        return "autoplace_" + GhostBlockData.getWorldIdentifier(world);
    }
    
    /**
     * 获取所有可用的用户保存文件名（不含内部文件如 clear_, undo_）。
     * @return 文件名列表
     */
    public static List<String> getAvailableFileNames() {
        List<String> files = new ArrayList<>();
        File savesDir = new File(GhostBlockData.SAVES_DIR);
        if (!savesDir.exists() || !savesDir.isDirectory()) {
            return files;
        }
        File[] jsonFiles = savesDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (jsonFiles != null) {
            for (File file : jsonFiles) {
                String name = file.getName();
                String baseName = name.substring(0, name.length() - 5);
                if (!baseName.isEmpty() && !baseName.toLowerCase().startsWith("clear_") && !baseName.toLowerCase().startsWith("undo_")) {
                    files.add(baseName);
                }
            }
        }
        Collections.sort(files, String.CASE_INSENSITIVE_ORDER);
        return files;
    }
    
    /**
     * 检查坐标是否有效且所在区块的 ExtendedBlockStorage 已就绪。
     * 这是比 `world.isBlockLoaded()` 更精确的检查，因为它确保了实际的方块存储数组存在。
     * @param world 客户端世界
     * @param pos 坐标
     * @return 如果就绪则为 true
     */
    public static boolean isBlockSectionReady(WorldClient world, BlockPos pos) {
        if (pos.getY() < 0 || pos.getY() >= 256) return false;
        if (!world.isBlockLoaded(pos)) return false; 
        
        Chunk chunk = world.getChunkFromBlockCoords(pos);
        int storageY = pos.getY() >> 4;
        
        return chunk.getBlockStorageArray()[storageY] != null;
    }

    /**
     * 在客户端世界设置一个幽灵方块。
     * @param world 客户端世界
     * @param pos 坐标
     * @param state 方块状态代理
     * @throws CommandException 如果方块ID无效
     */
    public static void setGhostBlock(WorldClient world, BlockPos pos, BlockStateProxy state) throws CommandException {
        if (world.isRemote) {
            Block block = Block.getBlockById(state.blockId);
            if (block != null) {
                world.setBlockState(pos, block.getStateFromMeta(state.metadata), 3);
                world.checkLightFor(EnumSkyBlock.BLOCK, pos);
                world.checkLightFor(EnumSkyBlock.SKY, pos);
            } else {
                LogUtil.error("log.error.setGhostBlock.invalidId", state.blockId);
                throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
            }
        }
    }
    
    /**
     * 为坐标参数提供Tab补全建议，按“玩家位置 -> 相对位置 -> 指针位置”的顺序。
     * @param sender 命令发送者
     * @param coordinateIndex 坐标索引 (0=x, 1=y, 2=z)
     * @param targetPos 玩家视线指向的方块坐标
     * @return 建议列表
     */
    public static List<String> getCoordinateSuggestions(ICommandSender sender, int coordinateIndex, BlockPos targetPos) {
        List<String> suggestions = new ArrayList<>();

        // 1. 添加玩家当前整数坐标
        if (sender instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) sender;
            int playerCoord = 0;
            switch (coordinateIndex) {
                case 0: playerCoord = (int) Math.floor(player.posX); break;
                case 1: playerCoord = (int) Math.floor(player.posY); break;
                case 2: playerCoord = (int) Math.floor(player.posZ); break;
            }
            suggestions.add(String.valueOf(playerCoord));
        } else if (targetPos == null) {
            suggestions.add("0");
        }
        
        // 2. 添加相对坐标 "~"
        suggestions.add("~");

        // 3. 添加目标方块坐标
        if (targetPos != null) {
            switch (coordinateIndex) {
                case 0: suggestions.add(String.valueOf(targetPos.getX())); break;
                case 1: suggestions.add(String.valueOf(targetPos.getY())); break;
                case 2: suggestions.add(String.valueOf(targetPos.getZ())); break;
            }
        }

        // 移除重复项并返回
        return suggestions.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 检查参数数组中（除最后一个正在输入的参数外）是否包含某个标志。
     * @param args 参数数组
     * @param flags 要检查的标志
     * @return 如果包含则为 true
     */
    public static boolean hasFlag(String[] args, String... flags) {
        for (int i = 0; i < args.length - 1; i++) {
            for (String flag : flags) {
                if (args[i].equalsIgnoreCase(flag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 不区分大小写地检查列表中是否包含某个字符串。
     * @param list 字符串列表
     * @param target 目标字符串
     * @return 如果包含则为 true
     */
    public static boolean containsIgnoreCase(List<String> list, String target) {
        if (target == null) return false;
        return list.stream().anyMatch(s -> target.equalsIgnoreCase(s));
    }
    
    /**
     * 格式化 ID 列表为逗号分隔的字符串。
     * @param ids ID集合
     * @return 格式化后的字符串
     */
    public static String formatIdList(Collection<?> ids) {
        return String.join(", ", ids.stream().map(Object::toString).collect(Collectors.toList()));
    }
    
    /**
     * 创建带格式的进度消息组件。
     * @param key 语言文件键
     * @param percent 进度百分比
     * @param progressBar 进度条字符串
     * @return 格式化后的 IChatComponent
     */
    public static IChatComponent createProgressMessage(String key, int percent, String progressBar) {
        String rawMessage = LangUtil.translate(key, "{0}", "{1}");
        String[] parts = rawMessage.split("\\{(\\d)\\}", -1);
        IChatComponent message = new ChatComponentText("");
        message.appendSibling(new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default")).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY)));
        if (parts.length > 0) message.appendSibling(new ChatComponentText(parts[0]).setChatStyle(new ChatStyle().setColor(LABEL_COLOR)));
        message.appendSibling(new ChatComponentText(percent + "%").setChatStyle(new ChatStyle().setColor(VALUE_COLOR)));
        if (parts.length > 1) message.appendSibling(new ChatComponentText(parts[1]).setChatStyle(new ChatStyle().setColor(LABEL_COLOR)));
        message.appendSibling(new ChatComponentText(progressBar));
        if (parts.length > 2) message.appendSibling(new ChatComponentText(parts[2]).setChatStyle(new ChatStyle().setColor(LABEL_COLOR)));
        return message;
    }
    
    /**
     * 创建进度条样式的字符串。
     * @param progressPercent 进度百分比 (0-100)
     * @param length 进度条总长度
     * @return 格式化后的进度条字符串
     */
    public static String createProgressBar(float progressPercent, int length) {
        int progress = (int) (progressPercent / 100 * length);
        progress = Math.min(progress, length);
        progress = Math.max(0, progress);
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.GREEN);
        for (int i = 0; i < progress; i++) sb.append("=");
        if (progress < length) {
            sb.append(EnumChatFormatting.GOLD).append(">");
            sb.append(EnumChatFormatting.GRAY);
            for (int i = progress + 1; i < length; i++) sb.append("-");
        }
        return sb.toString();
    }
}