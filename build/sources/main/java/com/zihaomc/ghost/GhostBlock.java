package com.zihaomc.ghost;

import com.zihaomc.ghost.commands.GhostBlockCommand;
import com.zihaomc.ghost.proxy.CommonProxy;
import com.zihaomc.ghost.proxy.ClientProxy;
import com.zihaomc.ghost.proxy.ServerProxy;
import net.minecraft.init.Blocks;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = "ghostblock", name = "GhostBlock", version = "0.1.0", acceptableRemoteVersions = "*")
public class GhostBlock {

    @Mod.Instance
    public static GhostBlock instance;

    @SidedProxy(clientSide = "com.zihaomc.ghost.proxy.ClientProxy", serverSide = "com.zihaomc.ghost.proxy.ServerProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        System.out.println("Pre Initialization");
        // 强制加载原版方块注册
        Blocks.fire.getClass(); // 触发静态初始化
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        System.out.println("Initialization");
        // 仅客户端注册
        if (event.getSide() == Side.CLIENT) {
            GhostBlockCommand.register();
        }
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        System.out.println("Post Initialization");
    }
}
