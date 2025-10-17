package com.zihaomc.ghost;

import com.zihaomc.ghost.commands.GhostBlockCommand;
import com.zihaomc.ghost.commands.GhostConfigCommand;
import com.zihaomc.ghost.commands.TranslateCommand;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.handlers.CacheSavingHandler; 
import com.zihaomc.ghost.handlers.ChatSuggestEventHandler;
import com.zihaomc.ghost.handlers.GuiStateSaveHandler;
import com.zihaomc.ghost.handlers.ItemTooltipTranslationHandler;
import com.zihaomc.ghost.handlers.SignTranslationHandler;
import com.zihaomc.ghost.handlers.KeybindHandler;
import com.zihaomc.ghost.features.autosneak.AutoSneakHandler;
import com.zihaomc.ghost.features.playeresp.PlayerESPHandler;
import com.zihaomc.ghost.features.gameplay.FastPistonBreakingHandler;
import com.zihaomc.ghost.features.visual.PlayerArrowRendererHandler;
import com.zihaomc.ghost.proxy.CommonProxy;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.init.Blocks;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    public static final Logger logger = LogManager.getLogger(MODID);

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
        LogUtil.info("log.lifecycle.preinit", NAME);
        // 触发方块类的静态初始化 (有时需要)
        Blocks.fire.toString();

        // 初始化配置
        File configFile = event.getSuggestedConfigurationFile();
        GhostConfig.init(configFile);

        proxy.preInit(event);

        // 仅在客户端注册事件处理器
        if (event.getSide() == Side.CLIENT) {
            
            ItemTooltipTranslationHandler.loadCacheFromFile();
            MinecraftForge.EVENT_BUS.register(new CacheSavingHandler());
            LogUtil.debug("log.feature.cache.init");
            
            MinecraftForge.EVENT_BUS.register(new ChatSuggestEventHandler());
            LogUtil.debug("log.handler.registered.chatSuggest");

            MinecraftForge.EVENT_BUS.register(new AutoSneakHandler());
            LogUtil.debug("log.handler.registered.autoSneak");

            MinecraftForge.EVENT_BUS.register(new PlayerESPHandler());
            LogUtil.debug("log.handler.registered.playerEsp");
            
            MinecraftForge.EVENT_BUS.register(new FastPistonBreakingHandler());
            LogUtil.debug("log.handler.registered.fastPiston");

            // v-- 注册全新的、正确的箭矢隐藏处理器 --v
            MinecraftForge.EVENT_BUS.register(new PlayerArrowRendererHandler());
            LogUtil.debug("log.handler.registered.playerArrow");
            // ^-- 注册结束 --^

            MinecraftForge.EVENT_BUS.register(new KeybindHandler());
            LogUtil.debug("log.handler.registered.keybind");
            
            MinecraftForge.EVENT_BUS.register(new SignTranslationHandler());
            LogUtil.debug("log.handler.registered.signTranslation");

            // v-- 注册GUI状态丢失修复处理器 --v
            MinecraftForge.EVENT_BUS.register(new GuiStateSaveHandler());
            LogUtil.debug("log.handler.registered.guiStateSave");
            // ^-- 注册结束 --^

            MinecraftForge.EVENT_BUS.register(new ItemTooltipTranslationHandler());
            LogUtil.debug("log.handler.registered.itemTooltip");
        }
    }

    /**
     * FML 初始化事件处理。
     * 主要进行命令注册和特定模块的初始化。
     * @param event 初始化事件对象
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LogUtil.info("log.lifecycle.init", NAME);

        proxy.init(event);

        if (event.getSide() == Side.CLIENT) {
            LogUtil.debug("log.command.registering.client");
            GhostBlockCommand.register();
            LogUtil.debug("log.handler.registered.ghostBlockCommand");
            ClientCommandHandler.instance.registerCommand(new GhostConfigCommand());
            LogUtil.debug("log.command.registered.ghostConfig");
            ClientCommandHandler.instance.registerCommand(new GhostBlockCommand());
            LogUtil.debug("log.command.registered.cgb");
            ClientCommandHandler.instance.registerCommand(new TranslateCommand());
            LogUtil.debug("log.command.registered.gtranslate");
        } else {
            LogUtil.debug("log.command.skipping.server");
        }
    }

    /**
     * FML 后初始化事件处理。
     * 可用于 Mod 间的交互。
     * @param event 后初始化事件对象
     */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LogUtil.info("log.lifecycle.postinit", NAME);
        proxy.postInit(event);
    }
}