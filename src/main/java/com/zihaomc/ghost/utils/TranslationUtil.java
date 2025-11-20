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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一翻译工具类。
 * 支持多种翻译源：
 * 1. GOOGLE (GTX): 谷歌翻译免费接口
 * 2. BING (Web): 模拟必应网页版 (支持 www.bing.com 和 cn.bing.com 自动切换)
 * 3. MYMEMORY: 免费翻译记忆库 (包含智能重试机制)
 * 4. NIUTRANS: 小牛翻译
 */
public class TranslationUtil {

    private static final Gson GSON = new Gson();
    public static final String ERROR_PREFIX = "GHOST_TRANSLATION_ERROR:";

    // --- 必应翻译专用 ---
    // 默认使用国际版，如果失败会自动切换到 cn.bing.com 并记住选择
    private static String bingBaseHost = "www.bing.com";
    
    private static String bingIG = null;
    private static String bingIID = null;
    private static String bingKey = null;
    private static String bingToken = null;
    private static long bingTokenTime = 0;
    
    // 保留 User-Agent 是为了防止最基本的 HTTP 403 拒绝
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern BING_IG_PATTERN = Pattern.compile("IG:\"([A-F0-9]+)\"");
    private static final Pattern BING_PARAMS_PATTERN = Pattern.compile("params_AbusePreventionHelper\\s*=\\s*\\[([0-9]+),\\s*\"([^\"]+)\",\\s*[^]]+\\]");

    static {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookieManager);
    }

    public static String translate(String sourceText) {
        String provider = GhostConfig.Translation.translationProvider.toUpperCase();
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
        }
        
        return result;
    }

    // --- Google GTX ---
    private static String translateGoogleGTX(String sourceText) throws Exception {
        String fromLang = GhostConfig.Translation.translationSourceLang;
        String toLang = GhostConfig.Translation.translationTargetLang;
        String encodedText = URLEncoder.encode(sourceText, "UTF-8");
        
        String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + fromLang + "&tl=" + toLang + "&dt=t&q=" + encodedText;
        
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
        String fromLangConfig = GhostConfig.Translation.translationSourceLang;
        String toLangConfig = GhostConfig.Translation.translationTargetLang;
        
        String toLang = "zh".equals(toLangConfig) ? "zh-CN" : toLangConfig;
        String fromLang = "auto".equals(fromLangConfig) ? "Autodetect" : fromLangConfig;

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

    // --- Bing Web (已移除多余的 Referer/Origin 头) ---
    private static String translateBingWeb(String sourceText) {
        try {
            return performBingTranslationInternal(sourceText);
        } catch (Exception e) {
            // 第一次失败，切换域名
            toggleBingHost();
            bingIG = null;
            
            try {
                // 第二次尝试
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

        String fromLang = GhostConfig.Translation.translationSourceLang;
        String toLang = GhostConfig.Translation.translationTargetLang;
        if ("zh".equals(toLang)) toLang = "zh-Hans";
        if ("auto".equals(fromLang)) fromLang = "auto-detect";

        String urlStr = "https://" + bingBaseHost + "/ttranslatev3?isVertical=1&&IG=" + bingIG + "&IID=" + bingIID;
        
        HttpURLConnection conn = createConnection(urlStr, "POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        
        // 已移除 Referer 和 Origin 头
        
        String postData = "fromLang=" + fromLang + "&to=" + toLang + "&token=" + bingToken + "&key=" + bingKey + "&text=" + URLEncoder.encode(sourceText, "UTF-8");
        
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
        if ("www.bing.com".equals(bingBaseHost)) {
            bingBaseHost = "cn.bing.com";
        } else {
            bingBaseHost = "www.bing.com";
        }
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