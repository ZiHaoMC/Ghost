package com.zihaomc.ghost.features.translation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.zihaomc.ghost.utils.LogUtil;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理物品翻译的持久化缓存。
 * 负责将翻译结果和隐藏状态从文件加载到内存，以及在游戏关闭时将其保存回文件。
 */
public class TranslationCacheManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String CACHE_FILE_NAME = "translation_cache.json";
    private static final String HIDDEN_ITEMS_FILE_NAME = "hidden_translations.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static File getCacheFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, CACHE_FILE_NAME);
    }

    private static File getHiddenItemsFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, HIDDEN_ITEMS_FILE_NAME);
    }

    public static Map<String, List<String>> loadCache() {
        File cacheFile = getCacheFile();
        if (!cacheFile.exists()) {
            LogUtil.info("log.info.cache.file.notFound");
            return new ConcurrentHashMap<>();
        }

        try (Reader reader = new FileReader(cacheFile)) {
            Type type = new TypeToken<ConcurrentHashMap<String, List<String>>>(){}.getType();
            Map<String, List<String>> loadedMap = GSON.fromJson(reader, type);

            if (loadedMap != null) {
                return new ConcurrentHashMap<>(loadedMap);
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            LogUtil.error("log.error.cache.loadFailed", e.getMessage());
        }

        return new ConcurrentHashMap<>();
    }

    public static Set<String> loadHiddenItems() {
        File hiddenFile = getHiddenItemsFile();
        if (!hiddenFile.exists()) {
            LogUtil.info("log.info.hidden.file.notFound");
            return Collections.newSetFromMap(new ConcurrentHashMap<>());
        }

        try (Reader reader = new FileReader(hiddenFile)) {
            Type type = new TypeToken<Set<String>>(){}.getType();
            Set<String> loadedSet = GSON.fromJson(reader, type);
            if (loadedSet != null) {
                // 创建一个由 ConcurrentHashMap 支持的新 Set，然后将加载的数据添加进去。
                Set<String> concurrentSet = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
                concurrentSet.addAll(loadedSet);
                return concurrentSet;
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            LogUtil.error("log.error.hidden.loadFailed", e.getMessage());
        }
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }


    public static void saveCache(Map<String, List<String>> cache) {
        if (cache == null || cache.isEmpty()) {
            File cacheFile = getCacheFile();
            // 如果缓存为空但文件存在，则删除文件以保持同步
            if(cacheFile.exists()) {
                cacheFile.delete();
            }
            LogUtil.info("log.info.cache.save.skipped");
            return;
        }

        File cacheFile = getCacheFile();
        try (Writer writer = new FileWriter(cacheFile)) {
            GSON.toJson(cache, writer);
        } catch (IOException e) {
            LogUtil.error("log.error.cache.saveFailed", e.getMessage());
        }
    }

    public static void saveHiddenItems(Set<String> hiddenItems) {
        if (hiddenItems == null) {
            return;
        }
        
        File hiddenFile = getHiddenItemsFile();
        // 如果列表为空但文件存在，删除它
        if(hiddenItems.isEmpty()) {
            if(hiddenFile.exists()){
                hiddenFile.delete();
            }
            return;
        }

        try (Writer writer = new FileWriter(hiddenFile)) {
            GSON.toJson(hiddenItems, writer);
            LogUtil.info("log.info.hidden.saved", hiddenItems.size());
        } catch (IOException e) {
            LogUtil.error("log.error.hidden.saveFailed", e.getMessage());
        }
    }
}