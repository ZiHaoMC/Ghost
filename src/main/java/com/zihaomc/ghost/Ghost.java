package com.zihaomc.ghost;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.proxy.CommonProxy;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Ghost Mod 主类 (优化版)
 */
@Mod(modid = Ghost.MODID, name = Ghost.NAME, version = Ghost.VERSION, clientSideOnly = true, acceptableRemoteVersions = "*")
public class Ghost {

    public static final String MODID = "ghost";
    public static final String VERSION = "0.2.0";
    public static final String NAME = "Ghost";
    public static final Logger logger = LogManager.getLogger(MODID);

    @Mod.Instance(MODID)
    public static Ghost instance;

    // 依然保留 ServerProxy 指向 CommonProxy，防止在服务端误装时报错
    @SidedProxy(clientSide = "com.zihaomc.ghost.proxy.ClientProxy", serverSide = "com.zihaomc.ghost.proxy.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LogUtil.info("log.lifecycle.preinit", NAME);

        // 配置文件初始化应尽早进行
        File configFile = event.getSuggestedConfigurationFile();
        GhostConfig.init(configFile);

        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LogUtil.info("log.lifecycle.init", NAME);
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LogUtil.info("log.lifecycle.postinit", NAME);
        proxy.postInit(event);
    }
}