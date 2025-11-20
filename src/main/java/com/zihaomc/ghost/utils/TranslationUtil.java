package com.zihaomc.ghost.utils;

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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class TranslationUtil {

    private static final String NIUTRANS_API_URL = "https://api.niutrans.com/NiuTransServer/translation";
    // Google Translate Free Endpoint (GTX)
    private static final String GOOGLE_API_URL = "https://translate.googleapis.com/translate_a/single";
    
    private static final Gson GSON = new Gson();
    
    // 语言无关的错误前缀
    public static final String ERROR_PREFIX = "GHOST_TRANSLATION_ERROR:";

    /**
     * 统一的翻译入口，根据配置文件选择引擎
     * @param sourceText 要翻译的原文
     * @return 翻译结果
     */
    public static String translate(String sourceText) {
        String provider = GhostConfig.Translation.translationProvider;
        if (provider != null && provider.equalsIgnoreCase("GOOGLE")) {
            return translateGoogle(sourceText);
        } else {
            return translateNiuTrans(sourceText);
        }
    }

    /**
     * 调用 Google Translate (GTX) 进行文本翻译
     */
    private static String translateGoogle(String sourceText) {
        String fromLang = GhostConfig.Translation.translationSourceLang;
        String toLang = GhostConfig.Translation.translationTargetLang;
        
        try {
            String encodedText = URLEncoder.encode(sourceText, "UTF-8");
            String urlStr = GOOGLE_API_URL + "?client=gtx&sl=" + fromLang + "&tl=" + toLang + "&dt=t&q=" + encodedText;
            
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0"); // Google 需要 User-Agent
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    
                    // Google 返回的是 JSON 数组：[[["翻译结果","原文",...], ...], ...]
                    JsonArray rootArray = GSON.fromJson(response.toString(), JsonArray.class);
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
                    } else {
                        return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.unknown_format");
                    }
                }
            } else {
                return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.http_error", responseCode);
            }
        } catch (Exception e) {
            LogUtil.printStackTrace("log.error.translation.exception", e, e.getMessage());
            return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.internal_error", e.getMessage());
        }
    }

    /**
     * 调用小牛翻译API进行文本翻译
     */
    private static String translateNiuTrans(String sourceText) {
        String apiKey = GhostConfig.Translation.niuTransApiKey;
        String fromLang = GhostConfig.Translation.translationSourceLang;
        String toLang = GhostConfig.Translation.translationTargetLang;

        if (apiKey == null || apiKey.trim().isEmpty()) {
            return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.no_api_key");
        }

        try {
            Map<String, String> params = new HashMap<>();
            params.put("from", fromLang);
            params.put("to", toLang);
            params.put("apikey", apiKey);
            params.put("src_text", URLEncoder.encode(sourceText, "UTF-8"));

            String urlParameters = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("&"));

            byte[] postData = urlParameters.getBytes(StandardCharsets.UTF_8);

            URL url = new URL(NIUTRANS_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    JsonObject jsonResponse = GSON.fromJson(response.toString(), JsonObject.class);
                    if (jsonResponse.has("tgt_text")) {
                        return jsonResponse.get("tgt_text").getAsString();
                    } else if (jsonResponse.has("error_msg")) {
                        return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.api_error",
                                jsonResponse.get("error_msg").getAsString(),
                                jsonResponse.get("error_code").getAsString());
                    } else {
                        return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.unknown_format");
                    }
                }
            } else {
                return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.http_error", responseCode);
            }

        } catch (Exception e) {
            LogUtil.printStackTrace("log.error.translation.exception", e, e.getMessage());
            return ERROR_PREFIX + LangUtil.translate("ghost.error.translation.internal_error", e.getMessage());
        }
    }
}