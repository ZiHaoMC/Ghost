package com.zihaomc.ghost;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

import java.util.Map;

@IFMLLoadingPlugin.Name("GhostMixinLoader")
@IFMLLoadingPlugin.MCVersion("1.8.9")
public class MixinLoader implements IFMLLoadingPlugin {

    public MixinLoader() {
        // 这是关键的初始化步骤
        MixinBootstrap.init();
        Mixins.addConfiguration("mixins.ghost.json");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0]; // 我们不需要 ASM Transformer，Mixin 会自己处理
    }

    @Override
    public String getModContainerClass() {
        return null; // 我们让 Forge 正常发现主 Mod 类
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // Do nothing
    }

    @Override
    public String getAccessTransformerClass() {
        return null; // 如果你需要，可以在这里返回 Access Transformer 类
    }
}