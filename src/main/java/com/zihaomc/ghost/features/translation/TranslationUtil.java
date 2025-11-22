package com.zihaomc.ghost.features.translation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.utils.LogUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一翻译工具类 (极速优化版)。
 * 特性：
 * 1. 多源支持 (Google GTX, Bing Web, MyMemory, NiuTrans)
 * 2. 智能域名切换 (Bing cn/www)
 * 3. 智能重试机制 (MyMemory)
 * 4. LRU 内存缓存 (防止重复请求)
 * 5. 线程池管理 (防止卡顿)
 * 6. Bing Token 预热 (加速首次翻译)
 */
public class TranslationUtil {

    private static final Gson GSON = new Gson();
    public static final String ERROR_PREFIX = "GHOST_TRANSLATION_ERROR:";

    // --- 线程池优化 ---
    // 使用缓存线程池，复用线程，减少 new Thread() 的开销，显著提升高频翻译时的性能
    private static final ExecutorService THREAD_POOL = Executors.newCachedThreadPool();

    // --- 内存缓存 (LRU) ---
    private static final int CACHE_SIZE = 500;
    private static final Map<String, String> MEMORY_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, String>(CACHE_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > CACHE_SIZE;
            }
        }
    );

    // --- 必应翻译专用 ---
    private static String bingBaseHost = "www.bing.com";
    private static String bingIG = null;
    private static String bingIID = null;
    private static String bingKey = null;
    private static String bingToken = null;
    private static long bingTokenTime = 0;
    
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern BING_IG_PATTERN = Pattern.compile("IG:\"([A-F0-9]+)\"");
    private static final Pattern BING_PARAMS_PATTERN = Pattern.compile("params_AbusePreventionHelper\\s*=\\s*\\[([0-9]+),\\s*\"([^\"]+)\",\\s*[^]]+\\]");

    static {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookieManager);
        
        // --- 预热优化 ---
        // 在类加载时（游戏启动或首次调用时）就在后台悄悄获取 Bing Token
        // 这样玩家第一次点击翻译时就不需要等待 Token 获取，直接秒翻
        runAsynchronously(() -> {
            try {
                // 优先尝试 cn，因为国内用户更多
                String tempHost = "cn.bing.com"; 
                fetchTokensFromHost(tempHost);
                // 如果获取成功，直接把当前 Host 设为 cn，避免之后的自动切换尝试
                bingBaseHost = tempHost; 
                // LogUtil.info("Bing token pre-fetched successfully from " + tempHost);
            } catch (Exception e) {
                // cn 失败则尝试 www，静默失败也无所谓，反正真正翻译时会重试
                try {
                    fetchTokensFromHost("www.bing.com");
                    bingBaseHost = "www.bing.com";
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * 统一的异步执行入口，替代 new Thread().start()
     * 使用线程池管理，效率更高
     */
    public static void runAsynchronously(Runnable task) {
        THREAD_POOL.submit(task);
    }

    public static String translate(String sourceText) {
        return translate(sourceText, null);
    }

    public static String translate(String sourceText, String providerOverride) {
        if (sourceText == null || sourceText.trim().isEmpty()) return sourceText;

        String provider = (providerOverride != null) ? providerOverride.toUpperCase() : GhostConfig.Translation.translationProvider.toUpperCase();
        
        String cacheKey = provider + "|" + GhostConfig.Translation.translationSourceLang + "|" + GhostConfig.Translation.translationTargetLang + "|" + sourceText;
        
        if (MEMORY_CACHE.containsKey(cacheKey)) {
            return MEMORY_CACHE.get(cacheKey);
        }

        String result;
        try {
            switch (provider) {
                case "GOOGLE":
                    result = translateGoogleGTX(sourceText);
                    break;
                case "BING":
                    result = translateBingWeb(sourceText);
                    break;
                case "MYMEMORY":
                    result = translateMyMemory(sourceText);
                    break;
                case "NIUTRANS":
                default:
                    result = translateNiuTrans(sourceText);
                    break;
            }
        } catch (Exception e) {
            LogUtil.printStackTrace("log.error.translation.exception", e, e.getMessage());
            return sourceText; 
        }

        if (result != null) {
            if (result.startsWith(ERROR_PREFIX) || result.contains("Please select two distinct languages")) {
                return sourceText;
            }
            MEMORY_CACHE.put(cacheKey, result);
        }
        
        return result;
    }

    // --- Google GTX (优化版: 使用 clients5 节点) ---
    private static String translateGoogleGTX(String sourceText) throws Exception {
        String[] langs = mapLanguageCodes("GOOGLE");
        String encodedText = URLEncoder.encode(sourceText, "UTF-8");
        
        // 优化：使用 clients5.google.com，通常比 translate.googleapis.com 响应更快且限制更少
        // client=dict-chrome-ex 是浏览器扩展使用的标识
        String urlStr = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=" + langs[0] + "&tl=" + langs[1] + "&q=" + encodedText;
        
        HttpURLConnection conn = createConnection(urlStr, "GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);

        if (conn.getResponseCode() == 200) {
            String response = readResponse(conn);
            // clients5 返回的格式通常比较简单，有时是嵌套数组
            JsonElement root = GSON.fromJson(response, JsonElement.class);
            
            if (root.isJsonArray()) {
                JsonArray rootArray = root.getAsJsonArray();
                if (rootArray.size() > 0) {
                    JsonElement firstItem = rootArray.get(0);
                    // 格式 1: [["翻译结果", "原文", ...]]
                    if (firstItem.isJsonArray()) {
                         return firstItem.getAsJsonArray().get(0).getAsString();
                    } 
                    // 格式 2: ["翻译结果"]
                    else if (firstItem.isJsonPrimitive()) {
                         return firstItem.getAsString();
                    }
                }
            }
        }
        return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.api_response", "Google", conn.getResponseCode());
    }

    // --- MyMemory ---
    private static String translateMyMemory(String sourceText) throws Exception {
        String[] langs = mapLanguageCodes("MYMEMORY");
        String fromLang = langs[0];
        String toLang = langs[1];

        boolean containsChinese = sourceText.codePoints().anyMatch(codepoint ->
                Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN);

        if ("Autodetect".equals(fromLang) && "zh-CN".equals(toLang) && containsChinese) {
            fromLang = "en";
        }
        
        String result = performMyMemoryRequest(sourceText, fromLang, toLang);

        if (result != null && result.contains("Please select two distinct languages")) {
             if (!"en".equals(fromLang)) {
                 return performMyMemoryRequest(sourceText, "en", toLang);
             }
             return result;
        }
        return result;
    }

    private static String performMyMemoryRequest(String sourceText, String fromLang, String toLang) throws Exception {
        String encodedText = URLEncoder.encode(sourceText, "UTF-8");
        String urlStr = "https://api.mymemory.translated.net/get?q=" + encodedText + "&langpair=" + fromLang + "|" + toLang;

        HttpURLConnection conn = createConnection(urlStr, "GET");

        if (conn.getResponseCode() == 200) {
            String response = readResponse(conn);
            JsonObject json = GSON.fromJson(response, JsonObject.class);

            if (json.has("responseData")) {
                JsonElement responseData = json.get("responseData");
                if (!responseData.isJsonNull()) {
                    return responseData.getAsJsonObject().get("translatedText").getAsString();
                }
            }
            if (json.has("responseDetails")) {
                return json.get("responseDetails").getAsString();
            }
        }
        return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.api_response", "MyMemory", conn.getResponseCode());
    }

    // --- Bing Web ---
    private static String translateBingWeb(String sourceText) {
        try {
            return performBingTranslationInternal(sourceText);
        } catch (Exception e) {
            toggleBingHost();
            bingIG = null;
            try {
                return performBingTranslationInternal(sourceText);
            } catch (Exception ex) {
                return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.bing.failed_domains");
            }
        }
    }

    private static String performBingTranslationInternal(String sourceText) throws Exception {
        // 这里会检查 Token 是否过期 (10分钟)
        refreshBingTokenIfNeeded();
        
        if (bingIG == null || bingKey == null || bingToken == null) {
            throw new Exception(LangUtil.translate("ghost.error.translation.bing.tokens"));
        }

        String[] langs = mapLanguageCodes("BING");
        
        String urlStr = "https://" + bingBaseHost + "/ttranslatev3?isVertical=1&&IG=" + bingIG + "&IID=" + bingIID;
        HttpURLConnection conn = createConnection(urlStr, "POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        
        String postData = "fromLang=" + langs[0] + "&to=" + langs[1] + "&token=" + bingToken + "&key=" + bingKey + "&text=" + URLEncoder.encode(sourceText, "UTF-8");
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() == 200) {
            String response = readResponse(conn);
            JsonArray jsonArray = GSON.fromJson(response, JsonArray.class);
            if (jsonArray != null && jsonArray.size() > 0) {
                JsonObject firstObj = jsonArray.get(0).getAsJsonObject();
                if (firstObj.has("translations")) {
                    return firstObj.getAsJsonArray("translations").get(0).getAsJsonObject().get("text").getAsString();
                }
            }
            throw new Exception(LangUtil.translate("ghost.error.translation.bing.empty"));
        }
        throw new Exception(LangUtil.translate("ghost.error.translation.api_response", "Bing", conn.getResponseCode()));
    }

    private static void refreshBingTokenIfNeeded() {
        // 检查 Token 是否为 null 或者是否已经超过 10 分钟
        if (bingIG == null || (System.currentTimeMillis() - bingTokenTime > 600000)) {
            try {
                fetchTokensFromHost(bingBaseHost);
            } catch (Exception e) {
                // 仅记录错误，不抛出异常，让调用者（performBingTranslationInternal）处理
                // 如果这里失败，performBingTranslationInternal 会检测到 bingIG 仍为 null 并抛出异常，触发外层的域名切换
                LogUtil.error("ghost.log.bing.token_error", e.getMessage());
            }
        }
    }

    private static void fetchTokensFromHost(String host) throws Exception {
        String urlStr = "https://" + host + "/translator";
        HttpURLConnection conn = createConnection(urlStr, "GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        
        if (conn.getResponseCode() == 200) {
            String html = readResponse(conn);
            Matcher igMatcher = BING_IG_PATTERN.matcher(html);
            if (igMatcher.find()) {
                bingIG = igMatcher.group(1);
                bingIID = bingIG + ".1";
            }
            Matcher paramMatcher = BING_PARAMS_PATTERN.matcher(html);
            if (paramMatcher.find()) {
                bingKey = paramMatcher.group(1);
                bingToken = paramMatcher.group(2);
            }
            
            if (bingIG != null && bingKey != null && bingToken != null) {
                bingTokenTime = System.currentTimeMillis();
            } else {
                throw new Exception("Token parse failed on " + host);
            }
        } else {
            throw new Exception("HTTP " + conn.getResponseCode() + " on " + host);
        }
    }

    private static void toggleBingHost() {
        bingBaseHost = "www.bing.com".equals(bingBaseHost) ? "cn.bing.com" : "www.bing.com";
        LogUtil.info("ghost.log.bing.switch_host", bingBaseHost);
    }

    // --- NiuTrans ---
    private static String translateNiuTrans(String sourceText) throws Exception {
        String apiKey = GhostConfig.Translation.niuTransApiKey;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.no_api_key");
        }
        
        Map<String, String> params = new HashMap<>();
        params.put("from", GhostConfig.Translation.translationSourceLang);
        params.put("to", GhostConfig.Translation.translationTargetLang);
        params.put("apikey", apiKey);
        params.put("src_text", URLEncoder.encode(sourceText, "UTF-8"));
        
        String urlParameters = params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
        byte[] postData = urlParameters.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = createConnection("https://api.niutrans.com/NiuTransServer/translation", "POST");
        try (OutputStream os = conn.getOutputStream()) { os.write(postData); }
        
        if (conn.getResponseCode() == 200) {
            String response = readResponse(conn);
            JsonObject json = GSON.fromJson(response, JsonObject.class);
            if (json.has("tgt_text")) return json.get("tgt_text").getAsString();
            if (json.has("error_msg")) return ERROR_PREFIX + json.get("error_msg").getAsString();
        }
        return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.api_response", "NiuTrans", conn.getResponseCode());
    }

    // --- Helpers ---
    
    private static String[] mapLanguageCodes(String provider) {
        String s = GhostConfig.Translation.translationSourceLang;
        String t = GhostConfig.Translation.translationTargetLang;

        if ("BING".equals(provider)) {
            if ("zh".equals(t)) t = "zh-Hans";
            if ("auto".equals(s)) s = "auto-detect";
        } else if ("MYMEMORY".equals(provider)) {
            if ("zh".equals(t)) t = "zh-CN";
            if ("auto".equals(s)) s = "Autodetect";
        }
        return new String[]{s, t};
    }

    private static HttpURLConnection createConnection(String urlStr, String method) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000); // 3秒连接超时
        conn.setReadTimeout(5000);    // 5秒读取超时
        conn.setRequestProperty("Connection", "keep-alive"); // 开启 Keep-Alive
        if ("POST".equals(method)) {
            conn.setDoOutput(true);
        }
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
}