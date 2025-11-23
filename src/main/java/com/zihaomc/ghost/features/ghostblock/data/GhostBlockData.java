package com.zihaomc.ghost.features.ghostblock.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class GhostBlockData {

    public static final Gson GSON = new Gson();
    // 注意：为了保持兼容性，配置文件的物理存储路径(config/Ghost/...)不需要改变
    // 这样玩家更新Mod后，旧数据依然存在
    private static final String CONFIG_DIR = "config/Ghost/GhostBlock/";
    public static final String SAVES_DIR = CONFIG_DIR + "saves/";
    private static final String FILE_EXTENSION = ".json";

    public static class GhostBlockEntry {
        public final int x, y, z;
        public final String blockId;
        public final int metadata;
        public final String originalBlockId;
        public final int originalMetadata;

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
        if (!savesDir.exists()) {
            savesDir.mkdirs();
        }

        if (fileName == null || fileName.isEmpty()) {
            return new File(savesDir, getWorldIdentifier(world) + FILE_EXTENSION);
        } else {
            return new File(savesDir, fileName + FILE_EXTENSION);
        }
    }

    public static void saveData(World world, List<GhostBlockEntry> entries, String fileName, boolean overwrite) {
        File file = getDataFile(world, fileName);
        file.getParentFile().mkdirs();

        List<GhostBlockEntry> entriesToSave;

        if (overwrite) {
            entriesToSave = new ArrayList<>(entries);
        } else {
            List<GhostBlockEntry> existingEntries = new ArrayList<>();
            if (file.exists()) {
                existingEntries = loadDataInternal(file);
            }

            Map<String, GhostBlockEntry> entryMap = new LinkedHashMap<>();
            for (GhostBlockEntry entry : existingEntries) {
                String key = entry.x + "," + entry.y + "," + entry.z;
                entryMap.put(key, entry);
            }
            for (GhostBlockEntry entry : entries) {
                String key = entry.x + "," + entry.y + "," + entry.z;
                entryMap.put(key, entry);
            }
            entriesToSave = new ArrayList<>(entryMap.values());
        }

        if (entriesToSave.isEmpty()) {
            if (file.exists()) {
                if (!file.delete()) {
                    LogUtil.error("log.error.data.deleteEmpty.failed", file.getPath());
                } else {
                    LogUtil.info("log.info.data.deleteEmpty.success", file.getPath());
                }
            }
            return;
        }

        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(entriesToSave, writer);
        } catch (IOException e) {
            LogUtil.printStackTrace("log.error.data.save.failed", e, file.getPath());
        }
    }

    private static List<GhostBlockEntry> loadDataInternal(File file) {
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try (Reader reader = new FileReader(file)) {
            java.lang.reflect.Type listType = new TypeToken<List<GhostBlockEntry>>() {}.getType();
            List<GhostBlockEntry> entries = GSON.fromJson(reader, listType);
            return entries != null ? entries : new ArrayList<>();
        } catch (Exception e) {
            LogUtil.printStackTrace("log.error.data.load.internal.failed", e, file.getPath());
            return new ArrayList<>();
        }
    }

    public static List<GhostBlockEntry> loadData(World world, List<String> fileNames) {
        List<GhostBlockEntry> allEntries = new ArrayList<>();
        for (String fileName : fileNames) {
            File file = getDataFile(world, fileName);
            if (!file.exists()) {
                if (fileName != null) {
                    LogUtil.info("log.info.data.load.notFound", fileName);
                }
                continue;
            }
            try (Reader reader = new FileReader(file)) {
                List<GhostBlockEntry> entries = GSON.fromJson(reader, new TypeToken<List<GhostBlockEntry>>() {}.getType());
                allEntries.addAll(entries);
            } catch (Exception e) {
                String displayName = (fileName == null) ? "default file" : fileName;
                LogUtil.printStackTrace("log.error.data.load.failed", e, displayName);
            }
        }
        return allEntries;
    }

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

                    if (serverIP.startsWith("[")) {
                        int closingBracket = serverIP.indexOf(']');
                        if (closingBracket != -1) {
                            host = serverIP.substring(1, closingBracket);
                            String portPart = serverIP.substring(closingBracket + 1);
                            if (portPart.startsWith(":")) {
                                try {
                                    port = Integer.parseInt(portPart.substring(1));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    } else {
                        int colonIndex = serverIP.lastIndexOf(':');
                        if (colonIndex != -1) {
                            try {
                                port = Integer.parseInt(serverIP.substring(colonIndex + 1));
                                host = serverIP.substring(0, colonIndex);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    return "server_" + sanitizeFileName(host + "_" + port);
                }
            }
        }
        return "unknown";
    }

    public static int getDimensionFromFileName(String fileName) {
        if (fileName == null) {
            LogUtil.error("log.error.data.getDim.nullFilename");
            return Integer.MIN_VALUE;
        }
        int dimIndex = fileName.lastIndexOf("_dim_");
        if (dimIndex != -1) {
            String dimStrWithExtension = fileName.substring(dimIndex + 5);
            String dimStr = dimStrWithExtension;
            if (dimStrWithExtension.toLowerCase().endsWith(".json")) {
                dimStr = dimStrWithExtension.substring(0, dimStrWithExtension.length() - 5);
            }
            try {
                return Integer.parseInt(dimStr);
            } catch (NumberFormatException e) {
                LogUtil.error("log.error.data.getDim.parseFailed", fileName, dimStr);
            }
        }
        LogUtil.warn("log.warn.data.getDim.notFound", fileName);
        return Integer.MIN_VALUE;
    }

    public static String getWorldIdentifier(World world) {
        return getWorldBaseIdentifier(world) + "_dim_" + world.provider.getDimensionId();
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public static void removeEntriesFromFile(World world, String fileName, List<BlockPos> positionsToRemove) {
        File file = getDataFile(world, fileName);
        if (!file.exists()) {
            return;
        }

        List<GhostBlockEntry> existingEntries = loadData(world, Collections.singletonList(fileName));

        Set<String> removalKeys = positionsToRemove.stream()
                .map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ())
                .collect(Collectors.toSet());

        List<GhostBlockEntry> newEntries = existingEntries.stream()
                .filter(entry -> !removalKeys.contains(entry.x + "," + entry.y + "," + entry.z))
                .collect(Collectors.toList());

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