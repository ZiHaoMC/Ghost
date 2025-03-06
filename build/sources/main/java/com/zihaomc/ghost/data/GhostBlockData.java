package com.zihaomc.ghost.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
import net.minecraft.server.integrated.IntegratedServer;
import java.io.*;
import java.util.*;

public class GhostBlockData {
    private static final Gson GSON = new Gson();
    private static final String CONFIG_DIR = "config/Ghost/GhostBlock/";
    private static final String FILE_EXTENSION = ".json";

    public static class GhostBlockEntry {
        public final int x, y, z;
        public final String blockId;
        public final int metadata;

        public GhostBlockEntry(BlockPos pos, String blockId, int metadata) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.blockId = blockId;
            this.metadata = metadata;
        }
    }

    public static File getDataFile(World world) {
        return new File(CONFIG_DIR + getWorldIdentifier(world) + FILE_EXTENSION);
    }

    private static String getWorldIdentifier(World world) {
        if (world.isRemote) {
            Minecraft mc = Minecraft.getMinecraft();
            
            if (mc.isSingleplayer()) {
                IntegratedServer server = mc.getIntegratedServer();
                if (server != null) {
                    String saveName = sanitizeFileName(server.getWorldName());
                    int dimension = world.provider.getDimensionId();
                    return "singleplayer/" + saveName + "/dim_" + dimension;
                }
                return "singleplayer/Unknown_World/dim_" + world.provider.getDimensionId();
            }
            
            ServerData server = mc.getCurrentServerData();
            if (server != null) {
                String serverIP = server.serverIP;
                String host = serverIP;
                int port = 25565;

                if (serverIP.startsWith("[")) {
                    int closingBracketIndex = serverIP.indexOf(']');
                    if (closingBracketIndex != -1) {
                        host = serverIP.substring(1, closingBracketIndex);
                        String remaining = serverIP.substring(closingBracketIndex + 1);
                        if (remaining.startsWith(":")) {
                            try {
                                port = Integer.parseInt(remaining.substring(1));
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

                String serverHash = sanitizeFileName(host + "_" + port);
                int dimension = world.provider.getDimensionId();
                return "servers/" + serverHash + "/dim_" + dimension;
            }
        }
        return "unknown/dim_" + world.provider.getDimensionId();
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9-_.]", "_");
    }

    public static void saveData(World world, List<GhostBlockEntry> newEntries) {
        List<GhostBlockEntry> existingEntries = loadData(world);
        Map<String, GhostBlockEntry> entryMap = new LinkedHashMap<>();

        for (GhostBlockEntry entry : existingEntries) {
            String key = entry.x + "," + entry.y + "," + entry.z;
            entryMap.put(key, entry);
        }

        for (GhostBlockEntry entry : newEntries) {
            String key = entry.x + "," + entry.y + "," + entry.z;
            entryMap.put(key, entry);
        }

        File file = getDataFile(world);
        file.getParentFile().mkdirs();

        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(new ArrayList<>(entryMap.values()), writer);
        } catch (IOException e) {
            System.err.println("[GhostBlock] 保存数据失败:");
            e.printStackTrace();
        }
    }

    public static List<GhostBlockEntry> loadData(World world) {
        File file = getDataFile(world);
        if (!file.exists()) {
            return new ArrayList<>();
        }

        try (Reader reader = new FileReader(file)) {
            return GSON.fromJson(reader, new TypeToken<List<GhostBlockEntry>>(){}.getType());
        } catch (IOException e) {
            System.err.println("[GhostBlock] 加载数据失败:");
            e.printStackTrace();
            return new ArrayList<>();
        } catch (Exception e) {
            System.err.println("[GhostBlock] 解析数据失败:");
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}