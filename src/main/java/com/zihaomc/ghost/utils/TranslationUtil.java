package com.zihaomc.ghost.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.LangUtil;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一翻译工具类 (最终优化版)。
 * 特性：
 * 1. 多源支持 (Google GTX, Bing Web, MyMemory, NiuTrans)
 * 2. 智能域名切换 (Bing cn/www)
 * 3. 智能重试机制 (MyMemory)
 * 4. LRU 内存缓存 (防止重复请求)
 * 5. 代码逻辑解耦
 */
public class TranslationUtil {

    private static final Gson GSON = new Gson();
    public static final String ERROR_PREFIX = "GHOST_TRANSLATION_ERROR:";

    // --- 内存缓存 (LRU) ---
    // 最多缓存 500 条最近的翻译结果，防止刷屏导致 API 封禁
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
    }

    /**
     * 使用默认配置的提供商进行翻译
     */
    public static String translate(String sourceText) {
        return translate(sourceText, null);
    }

    /**
     * 指定提供商进行翻译
     * @param sourceText 原文
     * @param providerOverride 指定的提供商 (GOOGLE, BING, etc.)，如果为 null 则使用配置文件
     * @return 翻译结果
     */
    public static String translate(String sourceText, String providerOverride) {
        if (sourceText == null || sourceText.trim().isEmpty()) return sourceText;

        // 确定使用的提供商
        String provider = (providerOverride != null) ? providerOverride.toUpperCase() : GhostConfig.Translation.translationProvider.toUpperCase();
        
        // 1. 检查缓存
        // Key 由 提供商 + 源语言 + 目标语言 + 原文 组成，确保唯一性
        String cacheKey = provider + "|" + GhostConfig.Translation.translationSourceLang + "|" + GhostConfig.Translation.translationTargetLang + "|" + sourceText;
        
        if (MEMORY_CACHE.containsKey(cacheKey)) {
            return MEMORY_CACHE.get(cacheKey);
        }

        // 2. 执行翻译
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

        // 3. 结果处理与缓存
        if (result != null) {
            if (result.startsWith(ERROR_PREFIX) || result.contains("Please select two distinct languages")) {
                return sourceText; // 失败返回原文，不缓存错误
            }
            // 成功，写入缓存
            MEMORY_CACHE.put(cacheKey, result);
        }
        
        return result;
    }

    // --- Google GTX ---
    private static String translateGoogleGTX(String sourceText) throws Exception {
        String[] langs = mapLanguageCodes("GOOGLE");
        String encodedText = URLEncoder.encode(sourceText, "UTF-8");
        
        String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + langs[0] + "&tl=" + langs[1] + "&dt=t&q=" + encodedText;
        
        HttpURLConnection conn = createConnection(urlStr, "GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);

        if (conn.getResponseCode() == 200) {
            String response = readResponse(conn);
            JsonArray rootArray = GSON.fromJson(response, JsonArray.class);
            if (rootArray != null && rootArray.size() > 0) {
                JsonArray sentencesArray = rootArray.get(0).getAsJsonArray();
                StringBuilder resultBuilder = new StringBuilder();
                for (JsonElement sentenceElement : sentencesArray) {
                    JsonArray sentence = sentenceElement.getAsJsonArray();
                    if (sentence.size() > 0 && !sentence.get(0).isJsonNull()) {
                        resultBuilder.append(sentence.get(0).getAsString());
                    }
                }
                return resultBuilder.toString();
            }
        }
        return ERROR_PREFIX + "Google API Error: " + conn.getResponseCode();
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
        return ERROR_PREFIX + "MyMemory API Error: " + conn.getResponseCode();
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
                return ERROR_PREFIX + "Bing failed on both domains.";
            }
        }
    }

    private static String performBingTranslationInternal(String sourceText) throws Exception {
        refreshBingTokenIfNeeded();
        if (bingIG == null || bingKey == null || bingToken == null) {
            throw new Exception("Failed to fetch tokens");
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
            throw new Exception("Bing returned empty body");
        }
        throw new Exception("Bing HTTP Error: " + conn.getResponseCode());
    }

    private static void refreshBingTokenIfNeeded() {
        if (bingIG == null || (System.currentTimeMillis() - bingTokenTime > 600000)) {
            try {
                fetchTokensFromHost();
            } catch (Exception e) {
                LogUtil.error("ghost.log.bing.token_error", e.getMessage());
            }
        }
    }

    private static void fetchTokensFromHost() throws Exception {
        String urlStr = "https://" + bingBaseHost + "/translator";
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
                throw new Exception("Token parse failed on " + bingBaseHost);
            }
        } else {
            throw new Exception("HTTP " + conn.getResponseCode() + " on " + bingBaseHost);
        }
    }

    private static void toggleBingHost() {
        bingBaseHost = "www.bing.com".equals(bingBaseHost) ? "cn.bing.com" : "www.bing.com";
        LogUtil.info("Bing host switched to: " + bingBaseHost);
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
        return ERROR_PREFIX + "NiuTrans Error: " + conn.getResponseCode();
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
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
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