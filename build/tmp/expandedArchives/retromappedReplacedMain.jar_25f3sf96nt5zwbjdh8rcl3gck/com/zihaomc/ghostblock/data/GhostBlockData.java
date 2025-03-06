package com.zihaomc.ghostblock.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class GhostBlockData {
    private static final Gson GSON = new Gson();
    private static final String CONFIG_DIR = "config/GhostBlock/";
    private static final String FILE_EXTENSION = ".json";

    public static class GhostBlockEntry {
        public final int x, y, z;
        public final String blockId;
        public final int metadata;

        public GhostBlockEntry(BlockPos pos, String blockId, int metadata) {
            this.x = pos.func_177958_n();
            this.y = pos.func_177956_o();
            this.z = pos.func_177952_p();
            this.blockId = blockId;
            this.metadata = metadata;
        }
    }

    // 保存数据到文件（按世界名称区分）
    public static void saveData(World world, List<GhostBlockEntry> entries) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();

        String worldName = world.func_72912_H().func_76065_j();
        File file = new File(CONFIG_DIR + worldName + FILE_EXTENSION);

        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(entries, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 从文件加载数据
    public static List<GhostBlockEntry> loadData(World world) {
        String worldName = world.func_72912_H().func_76065_j();
        File file = new File(CONFIG_DIR + worldName + FILE_EXTENSION);

        if (!file.exists()) return new ArrayList<>();

        try (Reader reader = new FileReader(file)) {
            return GSON.fromJson(reader, new TypeToken<List<GhostBlockEntry>>(){}.getType());
        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}