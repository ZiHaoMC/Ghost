package com.zihaomc.ghost;

import com.zihaomc.ghost.commands.GhostBlockCommand;
import com.zihaomc.ghost.commands.GhostConfigCommand;
import com.zihaomc.ghost.commands.TranslateCommand;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.handlers.CacheSavingHandler; // <-- 新增 Import
import com.zihaomc.ghost.handlers.ChatSuggestEventHandler;
import com.zihaomc.ghost.handlers.ItemTooltipTranslationHandler;
import com.zihaomc.ghost.handlers.SignTranslationHandler;
import com.zihaomc.ghost.handlers.KeybindHandler; // 确保导入路径正确
import com.zihaomc.ghost.features.autosneak.AutoSneakHandler;
import com.zihaomc.ghost.features.playeresp.PlayerESPHandler;
// import com.zihaomc.ghost.features.bedrockminer.BedrockMinerHandler;
import com.zihaomc.ghost.features.gameplay.FastPistonBreakingHandler;
import com.zihaomc.ghost.proxy.CommonProxy;
import net.minecraft.init.Blocks;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import java.io.File;

/**
 * Ghost Mod 的主类。
 * 负责 Mod 的加载、初始化、事件注册和命令注册。
 */
@Mod(modid = Ghost.MODID, name = Ghost.NAME, version = Ghost.VERSION, acceptableRemoteVersions = "*")
public class Ghost {

    // Mod 的常量信息
    public static final String MODID = "ghost";
    public static final String VERSION = "0.1.1";
    public static final String NAME = "Ghost";

    /** Mod 的实例 */
    @Mod.Instance(MODID)
    public static Ghost instance;

    /** 侧代理，根据运行环境（客户端/服务端）加载不同的代理类 */
    @SidedProxy(clientSide = "com.zihaomc.ghost.proxy.ClientProxy", serverSide = "com.zihaomc.ghost.proxy.ServerProxy")
    public static CommonProxy proxy;

    /**
     * FML 预初始化事件处理。
     * 主要进行配置加载和客户端事件注册。
     * @param event 预初始化事件对象
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        System.out.println("[" + NAME + "] 预初始化阶段");
        // 触发方块类的静态初始化 (有时需要)
        Blocks.fire.toString();

        // 初始化配置
        File configFile = event.getSuggestedConfigurationFile();
        GhostConfig.init(configFile);

        proxy.preInit(event);

        // 仅在客户端注册事件处理器
        if (event.getSide() == Side.CLIENT) {
            
            // v-- 这里是修改的核心 --v
            // 1. 从文件加载翻译缓存
            ItemTooltipTranslationHandler.loadCacheFromFile();
            
            // 2. 移除旧的、不可靠的JVM关闭钩子
            // Runtime.getRuntime().addShutdownHook(new Thread(ItemTooltipTranslationHandler::saveCacheToFile));

            // 3. 注册新的、可靠的缓存保存处理器
            MinecraftForge.EVENT_BUS.register(new CacheSavingHandler());
            System.out.println("[" + MODID + "-DEBUG] 翻译缓存已加载，并已注册保存事件处理器。");
            // ^-- 修改结束 --^
            
            // 注册聊天建议事件处理器
            MinecraftForge.EVENT_BUS.register(new ChatSuggestEventHandler());
            System.out.println("[" + MODID + "-DEBUG] 聊天建议事件处理器已在 PreInit 中注册。");

            MinecraftForge.EVENT_BUS.register(new AutoSneakHandler());
            System.out.println("[" + MODID + "-DEBUG] 自动蹲伏事件处理器 (AutoSneakHandler) 已在 PreInit 中注册。");

            MinecraftForge.EVENT_BUS.register(new PlayerESPHandler());
            System.out.println("[" + MODID + "-DEBUG] 玩家ESP事件处理器 (PlayerESPHandler) 已在 PreInit 中注册。");
            
        //    MinecraftForge.EVENT_BUS.register(new BedrockMinerHandler());
        //    System.out.println("[" + MODID + "-DEBUG] 破基岩事件处理器 (BedrockMinerHandler) 已在 PreInit 中注册。");
            
            MinecraftForge.EVENT_BUS.register(new FastPistonBreakingHandler());
            System.out.println("[" + MODID + "-DEBUG] 快速破坏活塞事件处理器 (FastPistonBreakingHandler) 已在 PreInit 中注册。");

            // 注册按键绑定事件处理器
            MinecraftForge.EVENT_BUS.register(new KeybindHandler());
            System.out.println("[" + MODID + "-DEBUG] 按键绑定事件处理器 (KeybindHandler) 已在 PreInit 中注册。");
            
            MinecraftForge.EVENT_BUS.register(new SignTranslationHandler());
            System.out.println("[" + MODID + "-DEBUG] 告示牌翻译事件处理器 (SignTranslationHandler) 已在 PreInit 中注册。");

            // 注册物品提示框翻译事件处理器
            MinecraftForge.EVENT_BUS.register(new ItemTooltipTranslationHandler());
            System.out.println("[" + MODID + "-DEBUG] 物品提示框翻译事件处理器 (ItemTooltipTranslationHandler) 已在 PreInit 中注册。");
        }
    }

    /**
     * FML 初始化事件处理。
     * 主要进行命令注册和特定模块的初始化。
     * @param event 初始化事件对象
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        System.out.println("[" + NAME + "] 初始化阶段");

        // --- 修正：将事件传递给代理，这是让按键绑定注册的关键 ---
        proxy.init(event);

        // 仅在客户端进行命令注册
        if (event.getSide() == Side.CLIENT) {
            System.out.println("[" + MODID + "-DEBUG] 正在注册客户端命令...");

            // 1. 注册 GhostBlockCommand 的事件处理器
            GhostBlockCommand.register();
            System.out.println("[" + MODID + "-DEBUG] GhostBlockCommand 事件处理器已注册。");

            // 2. 注册 GhostConfigCommand 命令实例 (/gconfig)
            ClientCommandHandler.instance.registerCommand(new GhostConfigCommand());
            System.out.println("[" + MODID + "-DEBUG] GhostConfigCommand (/gconfig) 已注册。");

            // 3. 注册 GhostBlockCommand 命令实例 (/cgb)
            ClientCommandHandler.instance.registerCommand(new GhostBlockCommand());
            System.out.println("[" + MODID + "-DEBUG] /cgb 命令实例已注册。");
            
            // 4. 注册/gtranslate
            ClientCommandHandler.instance.registerCommand(new TranslateCommand());
            System.out.println("[" + MODID + "-DEBUG] TranslateCommand (/gtranslate) 已注册。");

        } else {
            System.out.println("[" + MODID + "-DEBUG] 在服务端跳过客户端命令注册。");
        }
    }

    /**
     * FML 后初始化事件处理。
     * 可用于 Mod 间的交互。
     * @param event 后初始化事件对象
     */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        System.out.println("[" + NAME + "] 后初始化阶段");
        
        proxy.postInit(event);
    }
}