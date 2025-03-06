package com.zihaomc.ghost;

import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class LangUtil {
    public static String translate(String key, Object... args) {
        return I18n.func_135052_a(key, args);
    }
}
