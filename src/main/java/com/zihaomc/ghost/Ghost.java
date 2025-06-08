package com.zihaomc.ghost;

import com.zihaomc.ghost.commands.GhostBlockCommand;
import com.zihaomc.ghost.commands.GhostConfigCommand;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.playeresp.PlayerESPHandler; // 新增导入
import com.zihaomc.ghost.handlers.ChatSuggestEventHandler;
import com.zihaomc.ghost.features.autosneak.AutoSneakHandler; 
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
    public static final String VERSION = "0.1.0"; // 如果你更新了版本，可以改这里
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

        // 仅在客户端注册事件处理器
        if (event.getSide() == Side.CLIENT) {
            // 注册聊天建议事件处理器
            MinecraftForge.EVENT_BUS.register(new ChatSuggestEventHandler());
            System.out.println("[" + MODID + "-DEBUG] 聊天建议事件处理器已在 PreInit 中注册。");

            MinecraftForge.EVENT_BUS.register(new AutoSneakHandler());
            System.out.println("[" + MODID + "-DEBUG] 自动蹲伏事件处理器 (AutoSneakHandler) 已在 PreInit 中注册。");

            // 新增：注册玩家ESP事件处理器
            MinecraftForge.EVENT_BUS.register(new PlayerESPHandler());
            System.out.println("[" + MODID + "-DEBUG] 玩家ESP事件处理器 (PlayerESPHandler) 已在 PreInit 中注册。");

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
            ClientCommandHandler.instance.registerCommand(new GhostBlockCommand()); // 确保 GhostBlockCommand 构造函数等是合适的
            System.out.println("[" + MODID + "-DEBUG] /cgb 命令实例已注册。");

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
        // 目前无操作
    }
}