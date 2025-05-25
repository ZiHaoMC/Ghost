package com.zihaomc.ghost.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.server.integrated.IntegratedServer;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class GhostBlockData {
    public static final Gson GSON = new Gson();
    private static final String CONFIG_DIR = "config/Ghost/GhostBlock/";
    public static final String SAVES_DIR = CONFIG_DIR + "saves/"; // 改为 public
    private static final String FILE_EXTENSION = ".json";

public static class GhostBlockEntry {
    public final int x, y, z;
    public final String blockId;
    public final int metadata;
    public final String originalBlockId; // 必须存在
    public final int originalMetadata;   // 必须存在

    public GhostBlockEntry(BlockPos pos, String blockId, int metadata, 
                          String originalBlockId, int originalMetadata) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.blockId = blockId;
        this.metadata = metadata;
        this.originalBlockId = originalBlockId;
        this.originalMetadata = originalMetadata;
    }
}

    public static File getDataFile(World world, String fileName) {
        File savesDir = new File(SAVES_DIR);
        if (!savesDir.exists()) savesDir.mkdirs();

        if (fileName == null || fileName.isEmpty()) {
            // 使用世界标识作为文件名，不创建子目录
            return new File(savesDir, getWorldIdentifier(world) + FILE_EXTENSION);
        } else {
            return new File(savesDir, fileName + FILE_EXTENSION);
        }
    }

    public static void saveData(World world, List<GhostBlockEntry> entries, String fileName, boolean overwrite) {
    File file = getDataFile(world, fileName);
    file.getParentFile().mkdirs(); // 确保目录存在

    List<GhostBlockEntry> entriesToSave;

    if (overwrite) {
        entriesToSave = new ArrayList<>(entries); // 直接使用新条目
    } else {
        // 合并模式
        List<GhostBlockEntry> existingEntries = new ArrayList<>();
        if (file.exists()) {
             // 直接使用文件加载，避免 world 上下文问题
            existingEntries = loadDataInternal(file);
        }

        // 使用 Map 来处理基于坐标的合并/覆盖
        Map<String, GhostBlockEntry> entryMap = new LinkedHashMap<>();
        for (GhostBlockEntry entry : existingEntries) {
            String key = entry.x + "," + entry.y + "," + entry.z;
            entryMap.put(key, entry);
        }
        // 添加/替换为新条目
        for (GhostBlockEntry entry : entries) {
            String key = entry.x + "," + entry.y + "," + entry.z;
            entryMap.put(key, entry); // 如果合并则覆盖现有的
        }
        entriesToSave = new ArrayList<>(entryMap.values());
    }

    // 处理空列表：如果文件存在则删除它
    if (entriesToSave.isEmpty()) {
        if (file.exists()) {
            if (!file.delete()) {
                System.err.println("[GhostBlock] 删除空数据文件失败: " + file.getPath());
            } else {
                 System.out.println("[GhostBlock] 已删除空数据文件: " + file.getPath());
            }
        }
        return; // 无需进一步操作
    }

    // 如果不为空则写入数据
    try (Writer writer = new FileWriter(file)) {
        GSON.toJson(entriesToSave, writer);
    } catch (IOException e) {
        System.err.println("[GhostBlock] 保存数据到文件时出错: " + file.getPath());
        e.printStackTrace();
    }
}

    private static List<GhostBlockEntry> loadDataInternal(File file) {
     if (!file.exists()) {
        return new ArrayList<>();
     }
     try (Reader reader = new FileReader(file)) {
        // 指定类型Token
        java.lang.reflect.Type listType = new TypeToken<List<GhostBlockEntry>>(){}.getType();
        List<GhostBlockEntry> entries = GSON.fromJson(reader, listType);
        return entries != null ? entries : new ArrayList<>(); // 处理 GSON 可能返回 null 的情况
     } catch (Exception e) {
         System.err.println("[GhostBlock] 内部加载失败: " + file.getPath());
         e.printStackTrace();
         return new ArrayList<>();
     }
}

    public static List<GhostBlockEntry> loadData(World world, List<String> fileNames) {
    List<GhostBlockEntry> allEntries = new ArrayList<>();
    for (String fileName : fileNames) {
        File file = getDataFile(world, fileName);
        if (!file.exists()) {
            if (fileName != null) {
                System.out.println("[GhostBlock] 文件不存在: " + fileName);
            }
            continue;
        }
        try (Reader reader = new FileReader(file)) {
            List<GhostBlockEntry> entries = GSON.fromJson(reader, new TypeToken<List<GhostBlockEntry>>(){}.getType());
            allEntries.addAll(entries);
        } catch (Exception e) {
            System.err.println("[GhostBlock] 加载文件失败: " + (fileName == null ? "默认文件" : fileName));
            e.printStackTrace();
        }
    }
    return allEntries;
}

    // 新增方法：获取不包含维度的世界基础标识
public static String getWorldBaseIdentifier(World world) {
    if (world.isRemote) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isSingleplayer()) {
            IntegratedServer server = mc.getIntegratedServer();
            return server != null ? 
                "singleplayer_" + sanitizeFileName(server.getWorldName()) : 
                "singleplayer_unknown";
        } else {
            ServerData server = mc.getCurrentServerData();
            if (server != null) {
                String serverIP = server.serverIP;
                String host = serverIP;
                int port = 25565;

                // IPv6处理（如 [::1]:25565）
                if (serverIP.startsWith("[")) {
                    int closingBracket = serverIP.indexOf(']');
                    if (closingBracket != -1) {
                        host = serverIP.substring(1, closingBracket);
                        String portPart = serverIP.substring(closingBracket + 1);
                        if (portPart.startsWith(":")) {
                            try {
                                port = Integer.parseInt(portPart.substring(1));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                } else {
                    int colonIndex = serverIP.lastIndexOf(':');
                    if (colonIndex != -1) {
                        try {
                            port = Integer.parseInt(serverIP.substring(colonIndex + 1));
                            host = serverIP.substring(0, colonIndex);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                return "server_" + sanitizeFileName(host + "_" + port);
            }
        }
    }
    return "unknown";
}

// 原方法调整为基于基础标识拼接维度
public static String getWorldIdentifier(World world) {
    return getWorldBaseIdentifier(world) + "_dim_" + world.provider.getDimensionId();
}

    private static String sanitizeFileName(String name) {
        // 替换所有非字母、数字、下划线和短横线的字符为下划线
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
    
    public static void removeEntriesFromFile(World world, String fileName, List<BlockPos> positionsToRemove) {
    File file = getDataFile(world, fileName);
    if (!file.exists()) return;

    // 加载现有条目
    List<GhostBlockEntry> existingEntries = loadData(world, Collections.singletonList(fileName));
    
    // 创建位置哈希集合用于快速查找
    Set<String> removalKeys = positionsToRemove.stream()
        .map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ())
        .collect(Collectors.toSet());

    // 过滤需要保留的条目
    List<GhostBlockEntry> newEntries = existingEntries.stream()
        .filter(entry -> !removalKeys.contains(entry.x + "," + entry.y + "," + entry.z))
        .collect(Collectors.toList());

    // 保存或删除文件
    if (newEntries.isEmpty()) {
        file.delete();
    } else {
        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(newEntries, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
}
