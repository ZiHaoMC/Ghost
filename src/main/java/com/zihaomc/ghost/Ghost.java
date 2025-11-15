package com.zihaomc.ghost;

import com.zihaomc.ghost.features.autocraft.AutoCraftCommand;
import com.zihaomc.ghost.commands.AutoMineCommand;
import com.zihaomc.ghost.commands.GhostBlockCommand;
import com.zihaomc.ghost.commands.GhostConfigCommand;
import com.zihaomc.ghost.commands.TranslateCommand;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.autocraft.AutoCraftHandler;
import com.zihaomc.ghost.features.autocraft.AutoCraftRecipeManager;
import com.zihaomc.ghost.features.automine.AutoMineHandler;
import com.zihaomc.ghost.handlers.*;
import com.zihaomc.ghost.features.autosneak.AutoSneakHandler;
import com.zihaomc.ghost.features.automine.AutoMineTargetManager;
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

    public static final String MODID = "ghost";
    public static final String VERSION = "0.1.1";
    public static final String NAME = "Ghost";
    public static final Logger logger = LogManager.getLogger(MODID);

    @Mod.Instance(MODID)
    public static Ghost instance;

    @SidedProxy(clientSide = "com.zihaomc.ghost.proxy.ClientProxy", serverSide = "com.zihaomc.ghost.proxy.ServerProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LogUtil.info("log.lifecycle.preinit", NAME);
        Blocks.fire.toString();

        File configFile = event.getSuggestedConfigurationFile();
        GhostConfig.init(configFile);

        proxy.preInit(event);

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

            MinecraftForge.EVENT_BUS.register(new PlayerArrowRendererHandler());
            LogUtil.debug("log.handler.registered.playerArrow");

            MinecraftForge.EVENT_BUS.register(new KeybindHandler());
            LogUtil.debug("log.handler.registered.keybind");
            
            MinecraftForge.EVENT_BUS.register(new SignTranslationHandler());
            LogUtil.debug("log.handler.registered.signTranslation");

            MinecraftForge.EVENT_BUS.register(new ItemTooltipTranslationHandler());
            LogUtil.debug("log.handler.registered.itemTooltip");

            MinecraftForge.EVENT_BUS.register(new AutoMineHandler());
            LogUtil.debug("log.handler.registered.autoMine");

            MinecraftForge.EVENT_BUS.register(new AutoCraftHandler());
            LogUtil.debug("log.handler.registered.autoCraft");
            
            MinecraftForge.EVENT_BUS.register(new GhostBlockEventHandler());
            LogUtil.debug("log.handler.registered.ghostBlockCommand");
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LogUtil.info("log.lifecycle.init", NAME);

        proxy.init(event);
        
        ItemTooltipTranslationHandler.loadCacheFromFile();
        AutoMineTargetManager.loadTargets();
        AutoCraftRecipeManager.initialize(); // 在这里初始化所有自动合成配方

        if (event.getSide() == Side.CLIENT) {
            // --- 修正点: 将模式设置的逻辑移动到 init 阶段 ---
            try {
                // 这里只设置内部变量，不发送任何聊天消息
                AutoMineHandler.MiningMode mode = AutoMineHandler.MiningMode.valueOf(GhostConfig.AutoMine.miningMode.toUpperCase());
                AutoMineHandler.setCurrentMiningMode_noMessage(mode);
            } catch (IllegalArgumentException e) {
                LogUtil.warn("log.config.invalid.automineMode", GhostConfig.AutoMine.miningMode);
                AutoMineHandler.setCurrentMiningMode_noMessage(AutoMineHandler.MiningMode.SIMULATE);
            }
            
            LogUtil.debug("log.command.registering.client");
            
            ClientCommandHandler.instance.registerCommand(new GhostBlockCommand());
            LogUtil.debug("log.command.registered.cgb");
            
            ClientCommandHandler.instance.registerCommand(new GhostConfigCommand());
            LogUtil.debug("log.command.registered.ghostConfig");

            ClientCommandHandler.instance.registerCommand(new TranslateCommand());
            LogUtil.debug("log.command.registered.gtranslate");

            ClientCommandHandler.instance.registerCommand(new AutoMineCommand());
            LogUtil.debug("log.command.registered.autoMine");

            ClientCommandHandler.instance.registerCommand(new AutoCraftCommand());
            LogUtil.debug("log.command.registered.autoCraft");

        } else {
            LogUtil.debug("log.command.skipping.server");
        }
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LogUtil.info("log.lifecycle.postinit", NAME);
        proxy.postInit(event);
    }
}