package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.LangUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.event.world.WorldEvent;

import java.io.File;
import java.util.*;

public class GhostBlockCommand extends CommandBase {

    private static final Map<String, ClearConfirmation> pendingConfirmations = new HashMap<>();
    private static final int CONFIRMATION_TIMEOUT = 30 * 1000; // 30秒超时

    private static class ClearConfirmation {
        final long timestamp;
        final File targetFile;

        ClearConfirmation(File targetFile) {
            this.timestamp = System.currentTimeMillis();
            this.targetFile = targetFile;
        }
    }

    public static void register() {
        System.out.println("[GhostBlock-DEBUG] 注册事件监听...");
        MinecraftForge.EVENT_BUS.register(new CommandEventHandler());
        MinecraftForge.EVENT_BUS.register(GhostBlockCommand.class);
    }

    private static class CommandEventHandler {
        private boolean registered = false;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END && !registered) {
                ClientCommandHandler.instance.func_71560_a(new GhostBlockCommand());
                registered = true;
            }

            if (event.phase == TickEvent.Phase.END) {
                // 清除过期确认请求
                Iterator<Map.Entry<String, ClearConfirmation>> iter = pendingConfirmations.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<String, ClearConfirmation> entry = iter.next();
                    if (System.currentTimeMillis() - entry.getValue().timestamp > CONFIRMATION_TIMEOUT) {
                        iter.remove();
                    }
                }

                // 处理批量任务
                Iterator<FillTask> iterator = activeTasks.iterator();
                while (iterator.hasNext()) {
                    FillTask task = iterator.next();
                    if (task.processBatch()) {
                        iterator.remove();
                        System.out.println("[GhostBlock] 任务完成");
                    }
                }

                Iterator<LoadTask> loadIterator = activeLoadTasks.iterator();
                while (loadIterator.hasNext()) {
                    LoadTask task = loadIterator.next();
                    if (task.processBatch()) {
                        loadIterator.remove();
                        System.out.println("[GhostBlock] 加载任务完成");
                    }
                }
            }
        }
    }

    @Override
    public String func_71517_b() {
        return "cghostblock";
    }

    @Override
    public String func_71518_a(ICommandSender sender) {
        return LangUtil.translate("ghostblock.commands.usage");
    }

    @Override
    public boolean func_71519_b(ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> func_71514_a() {
        return Collections.singletonList("cgb");
    }

    @Override
    public void func_71515_b(ICommandSender sender, String[] args) throws CommandException {
        WorldClient world = Minecraft.func_71410_x().field_71441_e;
        if (world == null) throw new CommandException("ghostblock.commands.error.not_in_world");

        if (args.length < 1) throw new WrongUsageException(func_71518_a(sender));

        List<String> argsList = new ArrayList<>(Arrays.asList(args));
        boolean saveToFile = false;

        String subCommand = args[0];
        if ("set".equals(subCommand)) {
            if (args.length < 5) throw new WrongUsageException(func_71518_a(sender));

            List<String> setArgs = new ArrayList<>(Arrays.asList(args).subList(0, 5));
            List<String> optionalArgs = new ArrayList<>(Arrays.asList(args).subList(5, args.length));

            for (String arg : optionalArgs) {
                if (arg.equalsIgnoreCase("-s") || arg.equalsIgnoreCase("--save")) {
                    saveToFile = true;
                } else {
                    throw new WrongUsageException(func_71518_a(sender));
                }
            }

            args = setArgs.toArray(new String[0]);

            BlockPos pos = parseBlockPos(sender, args, 1);
            BlockStateProxy state = parseBlockState(args[4]);
            setGhostBlock(world, pos, state);
            if (saveToFile) {
                saveGhostBlocks(world, Collections.singletonList(pos), state);
            }
            sender.func_145747_a(formatMessage(EnumChatFormatting.GREEN, 
                "ghostblock.commands.cghostblock.set.success", 
                pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p()));

        } else if ("fill".equals(subCommand)) {
            boolean useBatch = false;
            int batchSize = 100;

            for (int i = 0; i < argsList.size(); i++) {
                String arg = argsList.get(i);
                if (arg.equalsIgnoreCase("-b") || arg.equalsIgnoreCase("--batch")) {
                    useBatch = true;
                    if (i + 1 < argsList.size() && isNumber(argsList.get(i + 1))) {
                        try {
                            batchSize = Integer.parseInt(argsList.get(i + 1));
                            argsList.remove(i + 1);
                        } catch (NumberFormatException e) {
                            throw new CommandException("ghostblock.commands.error.invalid_batch_size");
                        }
                    }
                    argsList.remove(i);
                    i--;
                } else if (arg.equalsIgnoreCase("-s") || arg.equalsIgnoreCase("--save")) {
                    saveToFile = true;
                    argsList.remove(i);
                    i--;
                }
            }
            args = argsList.toArray(new String[0]);

            if (args.length < 8) throw new WrongUsageException(func_71518_a(sender));

            BlockPos from = parseBlockPos(sender, args, 1);
            BlockPos to = parseBlockPos(sender, args, 4);
            BlockStateProxy state = parseBlockState(args[7]);

            List<BlockPos> allBlocks = new ArrayList<>();
            int minX = Math.min(from.func_177958_n(), to.func_177958_n());
            int maxX = Math.max(from.func_177958_n(), to.func_177958_n());
            int minY = Math.min(from.func_177956_o(), to.func_177956_o());
            int maxY = Math.max(from.func_177956_o(), to.func_177956_o());
            int minZ = Math.min(from.func_177952_p(), to.func_177952_p());
            int maxZ = Math.max(from.func_177952_p(), to.func_177952_p());

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        allBlocks.add(new BlockPos(x, y, z));
                    }
                }
            }

            if (useBatch) {
                activeTasks.add(new FillTask(world, state, allBlocks, batchSize, saveToFile));
                sender.func_145747_a(formatMessage("ghostblock.commands.cghostblock.fill.batch_started", 
                    allBlocks.size(), batchSize));
            } else {
                int count = fillGhostBlocks(world, from, to, state);
                if (saveToFile) {
                    saveGhostBlocks(world, allBlocks, state);
                }
                sender.func_145747_a(formatMessage(EnumChatFormatting.GREEN, 
                    "ghostblock.commands.cghostblock.fill.success", count));
            }
        } else if ("load".equals(subCommand)) {
            int loadBatchSize = 100;
            List<String> loadArgs = new ArrayList<>(Arrays.asList(args));
            for (int i = 0; i < loadArgs.size(); i++) {
                String arg = loadArgs.get(i);
                if (arg.equalsIgnoreCase("-b") || arg.equalsIgnoreCase("--batch")) {
                    if (i + 1 < loadArgs.size() && isNumber(loadArgs.get(i + 1))) {
                        try {
                            loadBatchSize = Integer.parseInt(loadArgs.get(i + 1));
                            loadArgs.remove(i + 1);
                        } catch (NumberFormatException e) {
                            throw new CommandException("ghostblock.commands.error.invalid_batch_size");
                        }
                    }
                    loadArgs.remove(i);
                    break;
                }
            }

            System.out.println("[GhostBlock-DEBUG] 手动触发加载...");
            List<GhostBlockData.GhostBlockEntry> entries = GhostBlockData.loadData(world);
            
            if (entries.isEmpty()) {
                sender.func_145747_a(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.empty"));
            } else {
                sender.func_145747_a(formatMessage(EnumChatFormatting.GREEN, 
                    "ghostblock.commands.load.start", entries.size()));
            }

            if (hasFlag(args, "-b", "--batch")) {
                activeLoadTasks.add(new LoadTask(world, entries, loadBatchSize));
                sender.func_145747_a(formatMessage("ghostblock.commands.load.batch_started", 
                    entries.size(), loadBatchSize));
            } else {
                int successCount = 0;
                for (GhostBlockData.GhostBlockEntry entry : entries) {
                    BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                    Block block = Block.func_149684_b(entry.blockId);
                    if (block != null) {
                        world.func_180501_a(pos, block.func_176203_a(entry.metadata), 3);
                        world.func_175689_h(pos);
                        successCount++;
                    }
                }
                sender.func_145747_a(formatMessage(EnumChatFormatting.GREEN, 
                    "ghostblock.commands.load.complete", successCount, entries.size()));
            }
        } else if ("clear".equalsIgnoreCase(subCommand)) {
            handleClearCommand(sender, world, args);
        } else {
            throw new WrongUsageException(func_71518_a(sender));
        }
    }

    private void handleClearCommand(ICommandSender sender, World world, String[] args) throws CommandException {
        if (args.length < 2 || !"file".equalsIgnoreCase(args[1])) {
            throw new WrongUsageException("ghostblock.commands.clear.usage");
        }

        File configFile = GhostBlockData.getDataFile(world);
        String senderKey = sender.func_70005_c_();

        if (args.length >= 3 && "confirm".equalsIgnoreCase(args[2])) {
            ClearConfirmation confirmation = pendingConfirmations.get(senderKey);
            if (confirmation == null) {
                throw new CommandException("ghostblock.commands.confirm.nothing_to_confirm");
            }

            if (System.currentTimeMillis() - confirmation.timestamp > CONFIRMATION_TIMEOUT) {
                pendingConfirmations.remove(senderKey);
                throw new CommandException("ghostblock.commands.confirm.timeout");
            }

            if (confirmation.targetFile.delete()) {
                pendingConfirmations.remove(senderKey);
                sender.func_145747_a(formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.clear.success"));
                GhostBlockData.loadData(world);
            } else {
                throw new CommandException("ghostblock.commands.clear.failed");
            }
        } else {
            if (!configFile.exists()) {
                throw new CommandException("ghostblock.commands.clear.no_file");
            }

            pendingConfirmations.put(senderKey, new ClearConfirmation(configFile));
            ChatComponentText message = formatMessage(EnumChatFormatting.GOLD, 
                "ghostblock.commands.clear.confirm_prompt", CONFIRMATION_TIMEOUT/1000);
            message.func_150257_a(new ChatComponentText("/cghostblock clear file confirm")
                .func_150255_a(new ChatStyle()
                    .func_150238_a(EnumChatFormatting.YELLOW)
                    .func_150227_a(true)
                ));
            sender.func_145747_a(message);
        }
    }

    private ChatComponentText formatMessage(String messageKey, Object... args) {
        return formatMessage(EnumChatFormatting.GRAY, messageKey, args);
    }

    private ChatComponentText formatMessage(EnumChatFormatting contentColor, String messageKey, Object... args) {
        ChatComponentText prefix = new ChatComponentText("[GhostBlock] ");
        prefix.func_150255_a(new ChatStyle().func_150238_a(EnumChatFormatting.DARK_GRAY));
        
        ChatComponentText content = new ChatComponentText(LangUtil.translate(messageKey, args));
        content.func_150255_a(new ChatStyle().func_150238_a(contentColor));
        
        prefix.func_150257_a(content);
        return prefix;
    }

    @Override
    public List<String> func_180525_a(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return func_71530_a(args, "set", "fill", "load", "clear");
        } else if (args.length == 2 && "clear".equalsIgnoreCase(args[0])) {
            return func_71530_a(args, "file");
        } else if (args.length == 3 && "clear".equalsIgnoreCase(args[0]) && "file".equalsIgnoreCase(args[1])) {
            return func_71530_a(args, "confirm");
        } else {
            String subCommand = args[0];
            if ("set".equalsIgnoreCase(subCommand)) {
                return handleSetTabCompletion(sender, args);
            } else if ("fill".equalsIgnoreCase(subCommand)) {
                return handleFillTabCompletion(sender, args);
            } else if ("load".equalsIgnoreCase(subCommand)) {
                return handleLoadTabCompletion(args);
            } else if ("clear".equalsIgnoreCase(subCommand)) {
                return handleClearTabCompletion(args);
            }
        }
        return Collections.emptyList();
    }

    private List<String> handleClearTabCompletion(String[] args) {
        if (args.length == 2) {
            return func_71530_a(args, "file");
        }
        return Collections.emptyList();
    }

    private List<String> handleSetTabCompletion(ICommandSender sender, String[] args) {
        int currentArgIndex = args.length - 1;
        if (currentArgIndex >= 1 && currentArgIndex <= 3) {
            int coordinateType = currentArgIndex - 1;
            List<String> suggestions = getCoordinateSuggestions(sender, coordinateType);
            return func_71530_a(args, suggestions.toArray(new String[0]));
        } else if (currentArgIndex == 4) {
            return getBlockNameSuggestions(args, args[4]);
        } else if (currentArgIndex >= 5) {
            List<String> suggestions = new ArrayList<>();
            if (!hasFlag(args, "-s", "--save")) {
                suggestions.add("-s");
                suggestions.add("--save");
            }
            return func_175762_a(args, suggestions);
        }
        return Collections.emptyList();
    }

    private List<String> handleFillTabCompletion(ICommandSender sender, String[] args) {
        int currentArgIndex = args.length - 1;

        int batchFlagIndex = getFlagIndex(args, "-b", "--batch");
        if (batchFlagIndex != -1 && currentArgIndex == batchFlagIndex + 1) {
            List<String> suggestions = new ArrayList<>(Arrays.asList("100", "500", "1000"));
            if (!hasFlag(args, "-s", "--save")) {
                suggestions.add("-s");
                suggestions.add("--save");
            }
            return func_175762_a(args, suggestions);
        }

        int baseArgCount = 8;
        boolean isOptionalArgPosition = currentArgIndex >= baseArgCount;

        if (isOptionalArgPosition) {
            List<String> suggestions = new ArrayList<>();
            if (!hasFlag(args, "-b", "--batch")) suggestions.addAll(Arrays.asList("-b", "--batch"));
            if (!hasFlag(args, "-s", "--save")) suggestions.addAll(Arrays.asList("-s", "--save"));
            return func_175762_a(args, suggestions);
        }

        if (currentArgIndex >= 1 && currentArgIndex <= 3) {
            int coordinateType = currentArgIndex - 1;
            List<String> suggestions = getCoordinateSuggestions(sender, coordinateType);
            return func_71530_a(args, suggestions.toArray(new String[0]));
        } else if (currentArgIndex >= 4 && currentArgIndex <= 6) {
            int coordinateType = currentArgIndex - 4;
            List<String> suggestions = getCoordinateSuggestions(sender, coordinateType);
            return func_71530_a(args, suggestions.toArray(new String[0]));
        } else if (currentArgIndex == 7) {
            return getBlockNameSuggestions(args, args[7]);
        }

        return Collections.emptyList();
    }

    private List<String> handleLoadTabCompletion(String[] args) {
        int currentArgIndex = args.length - 1;
        if (currentArgIndex == 1) {
            List<String> suggestions = new ArrayList<>();
            if (!hasFlag(args, "-b", "--batch")) {
                suggestions.addAll(Arrays.asList("-b", "--batch"));
            }
            return func_175762_a(args, suggestions);
        } else if (currentArgIndex == 2) {
            int flagIndex = getFlagIndex(args, "-b", "--batch");
            if (flagIndex != -1 && currentArgIndex == flagIndex + 1) {
                return func_71530_a(args, "100", "500", "1000");
            }
        }
        return Collections.emptyList();
    }

    private List<String> getCoordinateSuggestions(ICommandSender sender, int coordinateType) {
        List<String> suggestions = new ArrayList<>();
        if (sender instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) sender;
            double x = player.field_70165_t;
            double y = player.field_70163_u;
            double z = player.field_70161_v;
            int coordValue = 0;
            switch (coordinateType) {
                case 0: coordValue = (int) Math.floor(x); break;
                case 1: coordValue = (int) Math.floor(y); break;
                case 2: coordValue = (int) Math.floor(z); break;
            }
            suggestions.add(String.valueOf(coordValue));
            suggestions.add("~");
        }
        return suggestions;
    }

    private List<String> getBlockNameSuggestions(String[] args, String currentInput) {
        List<String> suggestions = new ArrayList<>();
        for (Object obj : Block.field_149771_c.func_148742_b()) {
            ResourceLocation res = (ResourceLocation) obj;
            suggestions.add(res.toString());
        }
        return func_175762_a(args, suggestions);
    }

    private BlockPos parseBlockPos(ICommandSender sender, String[] args, int index) throws CommandException {
        EntityPlayer player = sender instanceof EntityPlayer ? (EntityPlayer) sender : null;
        double baseX = (player != null) ? player.field_70165_t : 0;
        double baseY = (player != null) ? player.field_70163_u : 0;
        double baseZ = (player != null) ? player.field_70161_v : 0;

        double x = parseRelativeCoordinate(sender, args[index], baseX);
        double y = parseRelativeCoordinate(sender, args[index + 1], baseY);
        double z = parseRelativeCoordinate(sender, args[index + 2], baseZ);

        return new BlockPos(
                Math.floor(x),
                Math.floor(y),
                Math.floor(z)
        );
    }

    private double parseRelativeCoordinate(ICommandSender sender, String input, double base) throws CommandException {
        if (input.startsWith("~")) {
            if (!(sender instanceof EntityPlayer)) {
                throw new CommandException("ghostblock.commands.generic.permission");
            }
            String offsetStr = input.substring(1);
            if (offsetStr.isEmpty()) {
                return base;
            } else {
                try {
                    return base + Double.parseDouble(offsetStr);
                } catch (NumberFormatException e) {
                    throw new CommandException("ghostblock.commands.generic.num.invalid", input);
                }
            }
        } else {
            try {
                return Double.parseDouble(input);
            } catch (NumberFormatException e) {
                throw new CommandException("ghostblock.commands.generic.num.invalid", input);
            }
        }
    }

    private BlockStateProxy parseBlockState(String input) throws CommandException {
        try {
            System.out.println("[DEBUG] 解析方块输入: " + input);
            String[] parts = input.split(":");
            String domain = "minecraft";
            String path = "";
            int meta = 0;

            if (parts.length >= 2 && parts[parts.length - 1].matches("\\d+")) {
                meta = Integer.parseInt(parts[parts.length - 1]);
                input = input.substring(0, input.lastIndexOf(':'));
                parts = input.split(":");
            }

            if (parts.length == 1) {
                path = parts[0];
            } else if (parts.length >= 2) {
                domain = parts[0];
                path = parts[1];
            }

            String fullName = domain + ":" + path;
            Block block = Block.func_149684_b(fullName);

            if (block == null) {
                System.out.println("[ERROR] 无效的方块: " + fullName);
                throw new CommandException("ghostblock.commands.error.invalid_block");
            }

            try {
                block.func_176203_a(meta);
            } catch (Exception e) {
                System.out.println("[WARN] 元数据无效，重置为0");
                meta = 0;
            }

            if (block == Blocks.field_150350_a) {
                meta = 0;
            }

            System.out.println("[SUCCESS] 解析结果: " + block.getRegistryName() + " (ID=" + Block.func_149682_b(block) + "), meta=" + meta);
            return new BlockStateProxy(Block.func_149682_b(block), meta);
        } catch (Exception e) {
            throw new CommandException("ghostblock.commands.error.invalid_block");
        }
    }

    private void setGhostBlock(WorldClient world, BlockPos pos, BlockStateProxy state) throws CommandException {
        checkLoaded(world, pos);
        if (world.field_72995_K) {
            Block block = Block.func_149729_e(state.blockId);
            if (block != null) {
                world.func_180501_a(pos, block.func_176203_a(state.metadata), 3);
                world.func_175689_h(pos);

                System.out.println("[SUCCESS] 已设置幽灵方块: " + block.func_149732_F() + " at " + pos);
                if (block == Blocks.field_150350_a) {
                    System.out.println("[DEBUG] 空气方块已强制渲染更新");
                }
            } else {
                System.out.println("[ERROR] 尝试设置无效方块");
            }
        }
    }

    private int fillGhostBlocks(WorldClient world, BlockPos from, BlockPos to, BlockStateProxy state) throws CommandException {
        checkLoaded(world, from);
        checkLoaded(world, to);

        Block block = Block.func_149729_e(state.blockId);
        if (block == null) {
            throw new CommandException("ghostblock.commands.error.invalid_block");
        }

        int minX = Math.min(from.func_177958_n(), to.func_177958_n());
        int minY = Math.min(from.func_177956_o(), to.func_177956_o());
        int minZ = Math.min(from.func_177952_p(), to.func_177952_p());
        int maxX = Math.max(from.func_177958_n(), to.func_177958_n());
        int maxY = Math.max(from.func_177956_o(), to.func_177956_o());
        int maxZ = Math.max(from.func_177952_p(), to.func_177952_p());

        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    checkLoaded(world, pos);
                    world.func_180501_a(pos, block.func_176203_a(state.metadata), 3);
                    world.func_175689_h(pos);
                    count++;
                }
            }
        }
        return count;
    }

    private void saveGhostBlocks(WorldClient world, List<BlockPos> blocks, BlockStateProxy state) {
        List<GhostBlockData.GhostBlockEntry> entries = new ArrayList<>();
        Block block = Block.func_149729_e(state.blockId);
        if (block == null) {
            System.err.println("[GhostBlock] 错误: 无效的方块ID " + state.blockId);
            return;
        }
        String blockId = block.getRegistryName();

        for (BlockPos pos : blocks) {
            entries.add(new GhostBlockData.GhostBlockEntry(pos, blockId, state.metadata));
        }

        System.out.println("[GhostBlock] 正在保存 " + entries.size() + " 个幽灵方块");
        GhostBlockData.saveData(world, entries);
    }

    private static void checkLoaded(WorldClient world, BlockPos pos) throws CommandException {
        if (!world.func_72863_F().func_73149_a(pos.func_177958_n() >> 4, pos.func_177952_p() >> 4)) {
            throw new CommandException("ghostblock.commands.error.unloaded");
        }
        if (pos.func_177956_o() < 0 || pos.func_177956_o() >= 256) {
            throw new CommandException("ghostblock.commands.error.out_of_world");
        }
    }

    private static class BlockStateProxy {
        public final int blockId;
        public final int metadata;

        public BlockStateProxy(int blockId, int metadata) {
            this.blockId = blockId;
            this.metadata = metadata;
        }
    }

    private static class FillTask {
        final WorldClient world;
        final BlockStateProxy state;
        final List<BlockPos> remainingBlocks;
        final int batchSize;
        final boolean saveToFile;
        final List<GhostBlockData.GhostBlockEntry> savedEntries = new ArrayList<>();

        FillTask(WorldClient world, BlockStateProxy state, List<BlockPos> allBlocks, int batchSize, boolean saveToFile) {
            this.world = world;
            this.state = state;
            this.remainingBlocks = new ArrayList<>(allBlocks);
            this.batchSize = batchSize;
            this.saveToFile = saveToFile;
        }

        boolean processBatch() {
            Block block = Block.func_149729_e(state.blockId);
            if (block == null || remainingBlocks.isEmpty()) {
                System.out.println("[GhostBlock] 任务完成或无效方块");
                return true;
            }

            int processed = 0;
            while (processed < batchSize && !remainingBlocks.isEmpty()) {
                BlockPos pos = remainingBlocks.remove(0);
                try {
                    checkLoaded(world, pos);
                    world.func_180501_a(pos, block.func_176203_a(state.metadata), 3);
                    world.func_175689_h(pos);
                    System.out.println("[GhostBlock] 放置方块: " + pos);
                    processed++;

                    if (saveToFile) {
                        savedEntries.add(new GhostBlockData.GhostBlockEntry(
                            pos, 
                            block.getRegistryName(), state.metadata
                        ));
                    }
                } catch (CommandException e) {
                    System.out.println("[GhostBlock] 坐标无效: " + pos + ", 错误: " + e.getMessage());
                }
            }

            if (remainingBlocks.isEmpty() && saveToFile) {
                GhostBlockData.saveData(world, savedEntries);
            }

            return remainingBlocks.isEmpty();
        }
    }

    private static class LoadTask {
        private final WorldClient world;
        private final List<GhostBlockData.GhostBlockEntry> entries;
        private int currentIndex;
        private final int batchSize;

        LoadTask(WorldClient world, List<GhostBlockData.GhostBlockEntry> entries, int batchSize) {
            this.world = world;
            this.entries = entries;
            this.batchSize = batchSize;
            this.currentIndex = 0;
        }

        boolean processBatch() {
            if (currentIndex >= entries.size()) return true;

            int endIndex = Math.min(currentIndex + batchSize, entries.size());
            for (int i = currentIndex; i < endIndex; i++) {
                GhostBlockData.GhostBlockEntry entry = entries.get(i);
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                Block block = Block.func_149684_b(entry.blockId);
                if (block != null) {
                    world.func_180501_a(pos, block.func_176203_a(entry.metadata), 3);
                    world.func_175689_h(pos);
                    System.out.println("[GhostBlock] 加载方块: " + entry.blockId + " at " + pos);
                }
            }
            currentIndex = endIndex;
            return currentIndex >= entries.size();
        }
    }

    private static final List<FillTask> activeTasks = new ArrayList<>();
    private static final List<LoadTask> activeLoadTasks = new ArrayList<>();

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event) {
        System.out.println("[GhostBlock-DEBUG] 世界加载事件触发，是否为客户端世界？ " + event.world.field_72995_K);
        if (event.world.field_72995_K) {
            WorldClient world = Minecraft.func_71410_x().field_71441_e;
            if (world == null) {
                System.out.println("[GhostBlock-ERROR] 客户端世界未初始化");
                return;
            }

            System.out.println("[GhostBlock-DEBUG] 加载保存的幽灵方块数据...");
            List<GhostBlockData.GhostBlockEntry> entries = GhostBlockData.loadData(world);
            System.out.println("[GhostBlock-DEBUG] 加载到 " + entries.size() + " 个方块");

            for (GhostBlockData.GhostBlockEntry entry : entries) {
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                Block block = Block.func_149684_b(entry.blockId);
                if (block != null) {
                    world.func_180501_a(pos, block.func_176203_a(entry.metadata), 3);
                    world.func_175689_h(pos);
                    System.out.println("[GhostBlock-DEBUG] 恢复方块: " + entry.blockId + " at " + pos);
                } else {
                    System.out.println("[GhostBlock-ERROR] 无效方块ID: " + entry.blockId);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.field_72995_K) {
            activeTasks.clear();
            activeLoadTasks.clear();
        }
    }

    private boolean hasFlag(String[] args, String... flags) {
        for (String arg : args) {
            for (String flag : flags) {
                if (arg.equalsIgnoreCase(flag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getFlagIndex(String[] args, String... flags) {
        for (int i = 0; i < args.length; i++) {
            for (String flag : flags) {
                if (args[i].equalsIgnoreCase(flag)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean isNumber(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}