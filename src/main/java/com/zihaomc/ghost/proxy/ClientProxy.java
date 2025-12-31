package com.zihaomc.ghost.proxy;

import com.zihaomc.ghost.commands.GhostConfigCommand;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.autocraft.AutoCraftCommand;
import com.zihaomc.ghost.features.autocraft.AutoCraftHandler;
import com.zihaomc.ghost.features.autocraft.AutoCraftRecipeManager;
import com.zihaomc.ghost.features.automine.AutoMineCommand;
import com.zihaomc.ghost.features.automine.AutoMineHandler;
import com.zihaomc.ghost.features.automine.AutoMineTargetManager;
import com.zihaomc.ghost.features.autosneak.AutoSneakHandler;
import com.zihaomc.ghost.features.chat.ChatInputHandler;
import com.zihaomc.ghost.features.chat.CommandSuggestionHandler;
import com.zihaomc.ghost.features.gameplay.BuildGuessHandler;
import com.zihaomc.ghost.features.gameplay.FastPistonBreakingHandler;
import com.zihaomc.ghost.features.ghostblock.GhostBlockCommand;
import com.zihaomc.ghost.features.ghostblock.GhostBlockEventHandler;
import com.zihaomc.ghost.features.pathfinding.PathRenderer;
import com.zihaomc.ghost.features.pathfinding.PathfindingCommand;
import com.zihaomc.ghost.features.pathfinding.PathfindingHandler;
import com.zihaomc.ghost.features.playeresp.PlayerESPHandler;
import com.zihaomc.ghost.features.skyblock.DungeonChestHandler;
import com.zihaomc.ghost.features.skyblock.SkyblockPriceManager;
import com.zihaomc.ghost.features.translation.*;
import com.zihaomc.ghost.features.visual.PlayerArrowRendererHandler;
import com.zihaomc.ghost.handlers.KeybindHandler;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        // 1. 数据加载 (移至 init 阶段，确保安全)
        ItemTooltipTranslationHandler.loadCacheFromFile();
        AutoMineTargetManager.loadTargets();
        AutoCraftRecipeManager.initialize();
        SkyblockPriceManager.startUpdating(); // 启动价格更新后台线程

        // 2. 注册事件处理器
        registerEventHandlers();

        // 3. 注册客户端命令
        registerCommands();

        // 4. 注册按键绑定
        KeybindHandler.registerKeybinds();

        // 5. 初始化功能状态
        initAutoMineMode();
    }

    private void registerEventHandlers() {
        // 系统/核心功能
        MinecraftForge.EVENT_BUS.register(new CacheSavingHandler()); // 缓存保存
        MinecraftForge.EVENT_BUS.register(new KeybindHandler());     // 按键处理

        // 聊天与输入
        MinecraftForge.EVENT_BUS.register(new ChatInputHandler());
        MinecraftForge.EVENT_BUS.register(new CommandSuggestionHandler());

        // 翻译功能
        MinecraftForge.EVENT_BUS.register(new ChatTranslationHandler());
        MinecraftForge.EVENT_BUS.register(new SignTranslationHandler());
        MinecraftForge.EVENT_BUS.register(new ItemTooltipTranslationHandler());

        // 视觉与渲染
        MinecraftForge.EVENT_BUS.register(new PlayerESPHandler());
        MinecraftForge.EVENT_BUS.register(new PlayerArrowRendererHandler());
        MinecraftForge.EVENT_BUS.register(new PathRenderer()); // 寻路渲染

        // 游戏性辅助
        MinecraftForge.EVENT_BUS.register(new AutoSneakHandler());
        MinecraftForge.EVENT_BUS.register(new FastPistonBreakingHandler());
        MinecraftForge.EVENT_BUS.register(new BuildGuessHandler());

        // 自动化与 Skyblock
        MinecraftForge.EVENT_BUS.register(new AutoMineHandler());
        MinecraftForge.EVENT_BUS.register(new AutoCraftHandler());
        MinecraftForge.EVENT_BUS.register(new GhostBlockEventHandler());
        MinecraftForge.EVENT_BUS.register(new PathfindingHandler());
        MinecraftForge.EVENT_BUS.register(new DungeonChestHandler());

        LogUtil.debug("log.handler.registered.all");
    }

    private void registerCommands() {
        ClientCommandHandler.instance.registerCommand(new GhostBlockCommand());
        ClientCommandHandler.instance.registerCommand(new GhostConfigCommand());
        ClientCommandHandler.instance.registerCommand(new TranslateCommand());
        ClientCommandHandler.instance.registerCommand(new AutoMineCommand());
        ClientCommandHandler.instance.registerCommand(new AutoCraftCommand());
        ClientCommandHandler.instance.registerCommand(new PathfindingCommand());
        LogUtil.debug("log.command.registered.client");
    }

    private void initAutoMineMode() {
        try {
            AutoMineHandler.MiningMode mode = AutoMineHandler.MiningMode.valueOf(GhostConfig.AutoMine.miningMode.toUpperCase());
            AutoMineHandler.setCurrentMiningMode_noMessage(mode);
        } catch (IllegalArgumentException e) {
            LogUtil.warn("log.config.invalid.automineMode", GhostConfig.AutoMine.miningMode);
            AutoMineHandler.setCurrentMiningMode_noMessage(AutoMineHandler.MiningMode.SIMULATE);
        }
    }
}