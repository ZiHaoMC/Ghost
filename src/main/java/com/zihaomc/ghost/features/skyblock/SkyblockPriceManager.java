package com.zihaomc.ghost.features.skyblock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zihaomc.ghost.utils.LogUtil;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SkyblockPriceManager {
    // 线程安全的映射：物品ID -> 价格
    private static final Map<String, Double> prices = new ConcurrentHashMap<>();

    /**
     * 启动定时更新任务
     */
    public static void startUpdating() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            updateLbinPrices();   // 更新拍卖行最低价
            updateBazaarPrices(); // 更新Bazaar价格（精华、重组仪等）
        }, 0, 5, TimeUnit.MINUTES);
    }

    /**
     * 从 Moulberry API 获取 LBIN 价格
     */
    private static void updateLbinPrices() {
        try {
            // 目前最稳定的 Lowest BIN API
            URL url = new URL("https://moulberry.codes/lowestbin.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Ghost Mod)");

            if (conn.getResponseCode() == 200) {
                JsonObject json = new JsonParser().parse(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    prices.put(entry.getKey(), entry.getValue().getAsDouble());
                }
                LogUtil.info("Skyblock LBIN Prices updated.");
            }
        } catch (Exception e) {
            LogUtil.error("LBIN Update Failed: " + e.getMessage());
        }
    }

    /**
     * 从 Hypixel 官方 API 获取 Bazaar 价格
     */
    private static void updateBazaarPrices() {
        try {
            URL url = new URL("https://api.hypixel.net/skyblock/bazaar");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            
            if (conn.getResponseCode() == 200) {
                JsonObject json = new JsonParser().parse(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
                JsonObject products = json.getAsJsonObject("products");
                
                // 将地牢结算中常见的 Bazaar 物品存入 prices 映射
                addBazaarItem(products, "ESSENCE_WITHER");
                addBazaarItem(products, "ESSENCE_UNDEAD");
                addBazaarItem(products, "RECOMBOBULATOR_3000");
                addBazaarItem(products, "FUMING_POTATO_BOOK");
                addBazaarItem(products, "STOCK_OF_STONKS"); // 额外示例
                
                LogUtil.info("Skyblock Bazaar Prices updated.");
            }
        } catch (Exception e) {
            LogUtil.error("Bazaar Update Failed: " + e.getMessage());
        }
    }

    /**
     * 辅助方法：从 Bazaar 数据中提取卖出价
     */
    private static void addBazaarItem(JsonObject products, String id) {
        if (products.has(id)) {
            try {
                double price = products.get(id).getAsJsonObject()
                        .getAsJsonObject("quick_status")
                        .get("sellPrice").getAsDouble();
                prices.put(id, price);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 获取价格的公共方法
     */
    public static double getPrice(String id) {
        return prices.getOrDefault(id, 0.0);
    }
}