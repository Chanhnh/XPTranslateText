package tianci.dev.xptranslatetext2;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 用來翻譯多個 Segment - sử dụng Microsoft Translator API
 */
class MultiSegmentTranslateTask {
    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newFixedThreadPool(20);

    // Microsoft Translator API URLs
    private static final String TRANSLATE_URL = "https://api-edge.cognitive.microsofttranslator.com/translate";
    private static final String AUTH_URL = "https://edge.microsoft.com/translate/auth";

    // Cache cho authorization token và translation
    private static String authorizationToken = null;
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();

    public static void translateSegmentsAsync(
            final XC_MethodHook.MethodHookParam param,
            final int translationId,
            final List<Segment> segments,
            final String srcLang,
            final String tgtLang
    ) {
        TRANSLATION_EXECUTOR.submit(() -> {
            doTranslateSegments(segments, srcLang, tgtLang);
            new Handler(Looper.getMainLooper()).post(() -> {
                // Kiểm tra TextView Tag có còn đúng translationId không
                TextView tv = (TextView) param.thisObject;
                Object tagObj = tv.getTag();
                if (!(tagObj instanceof Integer)) {
                    XposedBridge.log("Tag mismatch => skip.");
                    return;
                }
                int currentTag = (Integer) tagObj;
                if (currentTag == translationId) {
                    // Áp dụng kết quả đã dịch
                    HookMain.applyTranslatedSegments(param, segments);
                } else {
                    XposedBridge.log("MultiSegmentTranslateTask => expired. currentTag=" + currentTag
                            + ", myId=" + translationId);
                }
            });
        });
    }

    private static void doTranslateSegments(List<Segment> mSegments, String srcLang, String tgtLang) {
        // Dịch từng segment
        for (Segment seg : mSegments) {
            String text = seg.text;

            if (text == null || text.trim().isEmpty()) {
                seg.translatedText = text;
                continue;
            }

            // Xử lý text bắt đầu bằng @ → chỉ dịch phần sau @
            if (text.startsWith("@")) {
                String raw = text.substring(1);
                String cacheKey = srcLang + ":" + tgtLang + ":" + raw;
                String translated = translateText(raw, srcLang, tgtLang, cacheKey);
                seg.translatedText = (translated == null) ? text : ("@" + translated);
                if (translated != null) translationCache.put(cacheKey, translated);
                continue;
            }

            // Kiểm tra cache
            String cacheKey = srcLang + ":" + tgtLang + ":" + text;
            if (translationCache.containsKey(cacheKey)) {
                seg.translatedText = translationCache.get(cacheKey);
                continue;
            }

            // Bỏ qua các text không cần dịch
            if (!isTranslationNeeded(text)) {
                seg.translatedText = text;
                continue;
            }

            // Dịch text bình thường
            String result = translateText(text, srcLang, tgtLang, cacheKey);
            if (result == null) {
                seg.translatedText = text; // fallback giữ nguyên
            } else {
                seg.translatedText = result;
                translationCache.put(cacheKey, result);
            }
        }
    }

    /** Dịch text bằng Microsoft Translator API */
    private static String translateText(String text, String srcLang, String tgtLang, String cacheKey) {
        return translateContent(text, srcLang, tgtLang, 0);
    }

    /** Thực hiện dịch với retry logic */
    private static String translateContent(String text, String from, String to, int retryCount) {
        if (retryCount > 2) return null;

        try {
            // Tách text thành từng dòng
            String[] lines = text.split("\n");
            JSONArray dataArray = new JSONArray();
            for (String line : lines) {
                JSONObject lineObj = new JSONObject();
                lineObj.put("Text", line);
                dataArray.put(lineObj);
            }

            // Tạo URL với query parameters
            StringBuilder urlBuilder = new StringBuilder(TRANSLATE_URL);
            urlBuilder.append("?api-version=3.0&to=").append(to);
            if (from != null && !from.isEmpty()) {
                urlBuilder.append("&from=").append(from);
            }

            URL url = new URL(urlBuilder.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + getAuthorizationToken());
            conn.setDoOutput(true);

            // Gửi request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = dataArray.toString().getBytes("UTF-8");
                os.write(input, 0, input.length);
                os.flush();
            }

            // Đọc response
            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                }

                String result = parseTranslationResult(sb.toString());
                if (result != null) {
                    return result;
                }
            } else {
                XposedBridge.log("Translation API error: " + conn.getResponseCode());
            }

        } catch (Exception e) {
            XposedBridge.log("[" + cacheKey + "] translate exception => " + e.getMessage());
        }

        // Nếu lỗi, clear token và thử lại
        authorizationToken = null;
        return translateContent(text, from, to, retryCount + 1);
    }

    /** Lấy authorization token */
    private static String getAuthorizationToken() {
        if (authorizationToken != null && !authorizationToken.isEmpty()) {
            return authorizationToken;
        }

        try {
            URL url = new URL(AUTH_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0");

            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                }
                authorizationToken = sb.toString();
                return authorizationToken;
            }
        } catch (Exception e) {
            XposedBridge.log("Failed to get authorization token: " + e.getMessage());
        }

        return null;
    }

    /** Parse kết quả dịch từ Microsoft API */
    private static String parseTranslationResult(String json) {
        try {
            JSONArray root = new JSONArray(json);
            StringBuilder result = new StringBuilder();

            for (int i = 0; i < root.length(); i++) {
                JSONObject item = root.getJSONObject(i);
                JSONArray translations = item.getJSONArray("translations");
                if (translations.length() > 0) {
                    String translatedText = translations.getJSONObject(0).getString("text");
                    result.append(translatedText);
                    if (i < root.length() - 1) {
                        result.append("\n");
                    }
                }
            }

            return result.toString();
        } catch (JSONException e) {
            XposedBridge.log("parseTranslationResult error => " + e.getMessage());
            return null;
        }
    }

    /** Quy tắc bỏ qua dịch */
    private static boolean isTranslationNeeded(String string) {
        // Bỏ qua các số
        if (string.matches("^\\d+([.:\\-]\\d+)*$")) {
            return false;
        }
        // Bỏ qua text trong ngoặc vuông
        if (string.matches("^\\[.*]$")) {
            return false;
        }
        return true;
    }
}
