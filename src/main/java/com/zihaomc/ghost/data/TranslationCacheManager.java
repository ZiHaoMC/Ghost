package com.zihaomc.ghost.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理物品翻译的持久化缓存。
 * 负责将翻译结果从文件加载到内存，以及在游戏关闭时将其保存回文件。
 */
public class TranslationCacheManager {

    // 使用与 GhostBlockData 相同的配置目录
    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String CACHE_FILE_NAME = "translation_cache.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 获取缓存文件的 File 对象。
     * @return 缓存文件的 File 对象。
     */
    private static File getCacheFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, CACHE_FILE_NAME);
    }

    /**
     * 从文件中加载翻译缓存。
     * @return 从文件加载的缓存 Map。如果文件不存在或加载失败，则返回一个空的 Map。
     */
    public static Map<String, List<String>> loadCache() {
        File cacheFile = getCacheFile();
        if (!cacheFile.exists()) {
            System.out.println("[Ghost-Cache] 翻译缓存文件不存在，将创建一个新的缓存。");
            return new ConcurrentHashMap<>();
        }

        try (Reader reader = new FileReader(cacheFile)) {
            Type type = new TypeToken<ConcurrentHashMap<String, List<String>>>(){}.getType();
            Map<String, List<String>> loadedMap = GSON.fromJson(reader, type);
            
            if (loadedMap != null) {
                // 确保返回的是线程安全的 ConcurrentHashMap
                return new ConcurrentHashMap<>(loadedMap);
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            System.err.println("[Ghost-Cache] 加载翻译缓存时出错: " + e.getMessage());
        }
        
        // 如果加载失败，返回一个空的 Map 以防止崩溃
        return new ConcurrentHashMap<>();
    }

    /**
     * 将内存中的翻译缓存保存到文件。
     * @param cache 需要保存的缓存 Map。
     */
    public static void saveCache(Map<String, List<String>> cache) {
        if (cache == null || cache.isEmpty()) {
            System.out.println("[Ghost-Cache] 内存中无翻译缓存，无需保存。");
            return;
        }

        File cacheFile = getCacheFile();
        try (Writer writer = new FileWriter(cacheFile)) {
            GSON.toJson(cache, writer);
        } catch (IOException e) {
            System.err.println("[Ghost-Cache] 保存翻译缓存时出错: " + e.getMessage());
        }
    }
}