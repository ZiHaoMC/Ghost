package com.zihaomc.ghost.utils;

import com.google.gson.Gson;
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

public class NiuTransUtil {

    private static final String API_URL = "https://api.niutrans.com/NiuTransServer/translation";
    private static final Gson GSON = new Gson();
    
    // 语言无关的错误前缀
    public static final String ERROR_PREFIX = "GHOST_TRANSLATION_ERROR:";

    /**
     * 调用小牛翻译API进行文本翻译
     * @param sourceText 要翻译的原文
     * @return 翻译结果，如果失败则返回带前缀的错误信息
     */
    public static String translate(String sourceText) {
        String apiKey = GhostConfig.niuTransApiKey;
        String fromLang = GhostConfig.translationSourceLang;
        String toLang = GhostConfig.translationTargetLang;

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

            URL url = new URL(API_URL);
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