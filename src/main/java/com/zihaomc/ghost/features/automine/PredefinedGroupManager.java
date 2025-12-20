
/*
 * This module is a derivative work of Baritone (https://github.com/cabaletta/baritone).
 * This module is licensed under the GNU LGPL v3.0.
 */

package com.zihaomc.ghost.features.automine;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理预定义的、硬编码的矿物组。
 */
public class PredefinedGroupManager {

    private static final Map<String, List<String>> PREDEFINED_GROUPS = new HashMap<>();

    static {
        // 初始化所有预定义的组
        PREDEFINED_GROUPS.put("skyblock:mithril", Arrays.asList(
            "minecraft:wool:7",
            "minecraft:prismarine",
            "minecraft:wool:11",
            "minecraft:stained_hardened_clay:9"
        ));
        PREDEFINED_GROUPS.put("skyblock:titanium", Arrays.asList(
            "minecraft:stone:4"
        ));
    }

    /**
     * 将所有预定义的组加载到用户的自定义组列表中（如果不存在）。
     * 这通常在首次启动或配置文件为空时调用。
     */
    public static void initializePredefinedGroups() {
        for (Map.Entry<String, List<String>> entry : PREDEFINED_GROUPS.entrySet()) {
            // putIfAbsent 确保我们不会覆盖用户可能已经修改或删除的同名组
            AutoMineTargetManager.customBlockGroups.putIfAbsent(entry.getKey(), entry.getValue());
        }
        AutoMineTargetManager.saveBlockGroups();
    }
    
    /**
     * 获取一个预定义组的组件列表。
     * @param groupName 组名
     * @return 组件列表，如果不存在则返回 null。
     */
    public static List<String> getGroup(String groupName) {
        return PREDEFINED_GROUPS.get(groupName.toLowerCase());
    }
}