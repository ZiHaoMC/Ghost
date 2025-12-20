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
 * 统一翻译工具类 (Bing 最终结构修复版)。
 * 
 * 核心修复：
 * 1. 修正了 Bing Token 的提取逻辑，正确识别 [Key(数字), Token(字符串), Expiry(数字)] 的结构。
 * 2. 增加了 IID 的提取逻辑，使请求更像真实浏览器。
 * 3. 增强了异常处理，防止出现 null 错误信息。
 */
public class TranslationUtil {

    private static final Gson GSON = new Gson();
    public static final String ERROR_PREFIX = "GHOST_TRANSLATION_ERROR:";

    // 使用缓存线程池，复用线程资源
    private static final ExecutorService THREAD_POOL = Executors.newCachedThreadPool();

    // LRU 内存缓存，防止重复翻译
    private static final int CACHE_SIZE = 500;
    private static final Map<String, String> MEMORY_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, String>(CACHE_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > CACHE_SIZE;
            }
        }
    );

    // --- 必应翻译专用状态 ---
    private static String bingBaseHost = "www.bing.com";
    private static String bingIG = null;
    private static String bingIID = null;
    private static String bingKey = null; // 注意：Key 是数字类型，但在请求中作为字符串发送
    private static String bingToken = null;
    private static long bingTokenTime = 0;
    
    // 模拟最新的 Chrome 浏览器 User-Agent
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    
    // IG 匹配正则: IG:"..."
    private static final Pattern BING_IG_PATTERN = Pattern.compile("IG:\"([^\"]+)\"");
    
    // IID 匹配正则: data-iid="..." (HTML 属性)
    private static final Pattern BING_IID_PATTERN = Pattern.compile("data-iid=\"([^\"]+)\"");

    // 关键修复：Token 匹配正则
    // 匹配 HTML 中的结构: var params_AbusePreventionHelper = [ 123456789, "TOKEN_STRING", 3600000 ];
    // Group 1: Key (数字)
    // Group 2: Token (字符串)
    // 使用 \\s* 兼容可能存在的任意空格
    private static final Pattern BING_AUTH_PATTERN = Pattern.compile("params_AbusePreventionHelper\\s*=\\s*\\[\\s*(\\d+)\\s*,\\s*\"([^\"]+)\"");

    static {
        // 全局 Cookie 管理，保持 Session
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookieManager);
        
        // 异步预热 Bing Token，减少首次翻译延迟
        runAsynchronously(() -> {
            try {
                // 优先尝试 www.bing.com
                fetchTokensFromHost("www.bing.com");
                bingBaseHost = "www.bing.com"; 
            } catch (Exception e) {
                // 失败则回退尝试 cn.bing.com
                try {
                    fetchTokensFromHost("cn.bing.com");
                    bingBaseHost = "cn.bing.com";
                } catch (Exception ignored) {}
            }
        });
    }

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
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            LogUtil.printStackTrace("log.error.translation.exception", e, msg);
            // 返回带前缀的错误信息，以便在游戏内显示红色警告
            return ERROR_PREFIX + msg; 
        }

        if (result != null) {
            // 如果是 API 返回的特定错误文本，不进行缓存
            if (result.startsWith(ERROR_PREFIX) || result.contains("Please select two distinct languages")) {
                return result; 
            }
            MEMORY_CACHE.put(cacheKey, result);
        }
        
        return result;
    }

    // --- Google GTX ---
    private static String translateGoogleGTX(String sourceText) throws Exception {
        String[] langs = mapLanguageCodes("GOOGLE");
        String encodedText = URLEncoder.encode(sourceText, "UTF-8");
        // 使用 clients5 节点，通常比 API 节点更稳定
        String urlStr = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=" + langs[0] + "&tl=" + langs[1] + "&q=" + encodedText;
        
        HttpURLConnection conn = createConnection(urlStr, "GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);

        if (conn.getResponseCode() == 200) {
            String response = readResponse(conn);
            JsonElement root = GSON.fromJson(response, JsonElement.class);
            
            // 解析 Google 返回的嵌套数组结构
            if (root.isJsonArray()) {
                JsonArray rootArray = root.getAsJsonArray();
                if (rootArray.size() > 0) {
                    JsonElement firstItem = rootArray.get(0);
                    if (firstItem.isJsonArray()) {
                         return firstItem.getAsJsonArray().get(0).getAsString();
                    } 
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

        // 简易的中文检测，用于优化 MyMemory 的自动检测
        boolean containsChinese = sourceText.codePoints().anyMatch(codepoint ->
                Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN);

        if ("Autodetect".equals(fromLang) && "zh-CN".equals(toLang) && containsChinese) {
            fromLang = "en";
        }
        
        String result = performMyMemoryRequest(sourceText, fromLang, toLang);

        // 如果 MyMemory 提示语言相同，尝试强制英语作为源语言
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

    // --- Bing Web (重点修复) ---
    private static String translateBingWeb(String sourceText) throws Exception {
        try {
            return performBingTranslationInternal(sourceText);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            LogUtil.warn("Bing translation failed on " + bingBaseHost + ": " + msg + ". Switching host...");
            
            toggleBingHost();
            bingIG = null; // 强制重置 Token，触发重新获取
            try {
                return performBingTranslationInternal(sourceText);
            } catch (Exception ex) {
                String exMsg = ex.getMessage() != null ? ex.getMessage() : "Unknown error";
                throw new Exception(LangUtil.translate("ghost.error.translation.bing.failed_domains") + ": " + exMsg);
            }
        }
    }

    private static String performBingTranslationInternal(String sourceText) throws Exception {
        refreshBingTokenIfNeeded();
        
        // 检查所有必要参数
        if (bingIG == null || bingKey == null || bingToken == null) {
            throw new Exception(LangUtil.translate("log.error.translation.bing.missing_params"));
        }

        String[] langs = mapLanguageCodes("BING");
        
        // 构造请求 URL，必须包含 IG 和 IID
        String urlStr = "https://" + bingBaseHost + "/ttranslatev3?isVertical=1&&IG=" + bingIG + "&IID=" + bingIID;
        HttpURLConnection conn = createConnection(urlStr, "POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        // Bing 严格检查 Referer，必须指向翻译主页
        conn.setRequestProperty("Referer", "https://" + bingBaseHost + "/translator");
        
        String postData = "fromLang=" + langs[0] + "&to=" + langs[1] + "&token=" + bingToken + "&key=" + bingKey + "&text=" + URLEncoder.encode(sourceText, "UTF-8");
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            String response = readResponse(conn);
            JsonElement root = GSON.fromJson(response, JsonElement.class);
            
            if (root.isJsonArray()) {
                JsonArray jsonArray = root.getAsJsonArray();
                if (jsonArray.size() > 0) {
                    JsonObject firstObj = jsonArray.get(0).getAsJsonObject();
                    if (firstObj.has("translations")) {
                        return firstObj.getAsJsonArray("translations").get(0).getAsJsonObject().get("text").getAsString();
                    }
                }
                throw new Exception(LangUtil.translate("ghost.error.translation.bing.empty"));
            } else if (root.isJsonObject()) {
                // 处理 Bing 返回的错误对象，避免 ClassCastException
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("statusCode") && obj.has("errorMessage")) {
                    throw new Exception("Bing API Error (" + obj.get("statusCode").getAsInt() + "): " + obj.get("errorMessage").getAsString());
                } else {
                    throw new Exception("Unexpected JSON Object: " + response);
                }
            } else {
                throw new Exception("Unexpected JSON format");
            }
        } else {
            // 读取错误流信息
            String errorMsg = "No details";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                errorMsg = br.lines().collect(Collectors.joining());
            } catch(Exception ignored){}
            throw new Exception("HTTP " + responseCode + ": " + errorMsg);
        }
    }

    private static void refreshBingTokenIfNeeded() throws Exception {
        // Token 10分钟过期，或者 IG 为空时重新获取
        if (bingIG == null || (System.currentTimeMillis() - bingTokenTime > 600000)) { 
            fetchTokensFromHost(bingBaseHost);
        }
    }

    private static void fetchTokensFromHost(String host) throws Exception {
        String urlStr = "https://" + host + "/translator";
        HttpURLConnection conn = createConnection(urlStr, "GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        
        if (conn.getResponseCode() == 200) {
            String html = readResponse(conn);
            
            // 1. 提取 IG
            Matcher igMatcher = BING_IG_PATTERN.matcher(html);
            if (igMatcher.find()) {
                bingIG = igMatcher.group(1);
                // IID 通常是 IG + ".1" 或者页面中指定的 data-iid
                // 尝试从页面提取 data-iid，如果失败则回退到 IG.1
                Matcher iidMatcher = BING_IID_PATTERN.matcher(html);
                if (iidMatcher.find()) {
                    bingIID = iidMatcher.group(1);
                } else {
                    bingIID = bingIG + ".1";
                }
            } else {
                throw new Exception(LangUtil.translate("ghost.error.translation.bing.tokens"));
            }
            
            // 2. 提取 Key 和 Token
            // 使用修正后的正则匹配 [Key(数字), Token(字符串)]
            Matcher authMatcher = BING_AUTH_PATTERN.matcher(html);
            if (authMatcher.find()) {
                bingKey = authMatcher.group(1);   // 第一个组是数字 (Key)
                bingToken = authMatcher.group(2); // 第二个组是字符串 (Token)
                bingTokenTime = System.currentTimeMillis();
            } else {
                throw new Exception(LangUtil.translate("log.error.translation.bing.structure_changed"));
            }
        } else {
            throw new Exception("Init HTTP " + conn.getResponseCode());
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
            // Bing 严格要求 zh-Hans
            if (t.toLowerCase().startsWith("zh")) t = "zh-Hans";
            if ("auto".equals(s)) s = "auto-detect";
        } else if ("MYMEMORY".equals(provider)) {
            if (t.toLowerCase().startsWith("zh")) t = "zh-CN";
            if ("auto".equals(s)) s = "Autodetect";
        } else if ("GOOGLE".equals(provider)) {
            if (t.equalsIgnoreCase("zh")) t = "zh-CN";
        }
        return new String[]{s, t};
    }

    private static HttpURLConnection createConnection(String urlStr, String method) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000); 
        conn.setReadTimeout(10000);    
        conn.setRequestProperty("Connection", "keep-alive"); 
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