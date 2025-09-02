package com.zihaomc.ghost.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zihaomc.ghost.config.GhostConfig;

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

    /**
     * 调用小牛翻译API进行文本翻译
     * @param sourceText 要翻译的原文
     * @return 翻译结果，如果失败则返回错误信息
     */
    public static String translate(String sourceText) {
        // 从配置中获取 API Key 和语言设置
        String apiKey = GhostConfig.niuTransApiKey;
        String fromLang = GhostConfig.translationSourceLang;
        String toLang = GhostConfig.translationTargetLang;

        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "§c错误: 未在配置中设置小牛翻译 API Key。请使用 /gconfig niuTransApiKey <您的key> 进行设置。";
        }

        try {
            // 1. 构建请求参数
            Map<String, String> params = new HashMap<>();
            params.put("from", fromLang);
            params.put("to", toLang);
            params.put("apikey", apiKey);
            // 对原文进行URL编码，防止特殊字符导致问题
            params.put("src_text", URLEncoder.encode(sourceText, "UTF-8"));

            String urlParameters = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("&"));

            byte[] postData = urlParameters.getBytes(StandardCharsets.UTF_8);

            // 2. 建立 HTTP 连接
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000); // 5秒连接超时
            conn.setReadTimeout(10000);   // 10秒读取超时

            // 3. 发送请求
            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            // 4. 读取响应
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    // 5. 解析 JSON 响应
                    JsonObject jsonResponse = GSON.fromJson(response.toString(), JsonObject.class);
                    if (jsonResponse.has("tgt_text")) {
                        return jsonResponse.get("tgt_text").getAsString();
                    } else if (jsonResponse.has("error_msg")) {
                        return "§c翻译API错误: " + jsonResponse.get("error_msg").getAsString() + 
                               " (代码: " + jsonResponse.get("error_code").getAsString() + ")";
                    } else {
                        return "§c翻译失败: 未知的API响应格式。";
                    }
                }
            } else {
                return "§c翻译失败: HTTP 错误码 " + responseCode;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "§c翻译时发生内部错误: " + e.getMessage();
        }
    }
}