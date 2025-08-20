package tianci.dev.xptranslatetext;

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

/** 用來翻譯多個 Segment */
class MultiSegmentTranslateTask {
    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newFixedThreadPool(20);
    // 簡易翻譯快取: (srcLang + tgtLang + text) -> translated
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
                TextView tv = (TextView) param.thisObject;
                Object tagObj = tv.getTag();
                if (!(tagObj instanceof Integer)) {
                    XposedBridge.log("Tag mismatch => skip.");
                    return;
                }
                int currentTag = (Integer) tagObj;
                if (currentTag == translationId) {
                    HookMain.applyTranslatedSegments(param, segments);
                } else {
                    XposedBridge.log("MultiSegmentTranslateTask => expired. currentTag=" + currentTag
                            + ", myId=" + translationId);
                }
            });
        });
    }

    private static void doTranslateSegments(List<Segment> mSegments, String srcLang, String tgtLang) {
        for (Segment seg : mSegments) {
            String text = seg.text;
            if (text == null || text.trim().isEmpty()) {
                seg.translatedText = text;
                continue;
            }

            // ⚡ Nếu text bắt đầu bằng @ → chỉ dịch phần sau @ và trả về kèm '@'
            if (text.startsWith("@")) {
                String raw = text.substring(1);
                String cacheKey = srcLang + ":" + tgtLang + ":" + raw;
                String translated = translateByLines(raw, cacheKey);
                seg.translatedText = (translated == null) ? text : ("@" + translated);
                if (translated != null) translationCache.put(cacheKey, translated);
                continue;
            }

            // 🔍 Cache bình thường
            String cacheKey = srcLang + ":" + tgtLang + ":" + text;
            if (translationCache.containsKey(cacheKey)) {
                seg.translatedText = translationCache.get(cacheKey);
                continue;
            }

            // ⛔ Bỏ qua dịch
            if (!isTranslationNeeded(text)) {
                seg.translatedText = text;
                continue;
            }

            // 🌐 Dịch
            String result = translateByLines(text, cacheKey);
            if (result == null) {
                seg.translatedText = text;
            } else {
                seg.translatedText = result;
                translationCache.put(cacheKey, result);
            }
        }
    }

    /** Dịch từng dòng riêng biệt để bảo toàn format xuống dòng */
    private static String translateByLines(String text, String cacheKey) {
        if (!text.contains("\n") && !text.contains("\r")) {
            return translateOnline(text, cacheKey);
        }
        String[] lines;
        String lineBreakType = "\n";
        if (text.contains("\r\n")) {
            lines = text.split("\r\n", -1);
            lineBreakType = "\r\n";
        } else if (text.contains("\n")) {
            lines = text.split("\n", -1);
            lineBreakType = "\n";
        } else {
            lines = text.split("\r", -1);
            lineBreakType = "\r";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String translatedLine;
            if (line.trim().isEmpty()) {
                translatedLine = line;
            } else {
                translatedLine = translateOnline(line, cacheKey);
                if (translatedLine == null) translatedLine = line;
            }
            result.append(translatedLine);
            if (i < lines.length - 1) result.append(lineBreakType);
        }
        return result.toString();
    }

    /** Gọi API localhost:3000/translate */
    private static String translateOnline(String text, String cacheKey) {
        try {
            URL url = new URL("http://localhost:3000/translate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setDoOutput(true);

            String payload = "{\"text\":\"" + escapeJson(text) + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes("UTF-8");
                os.write(input, 0, input.length);
                os.flush();
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = in.readLine()) != null) sb.append(line);
            }

            return parseLocalResult(sb.toString());

        } catch (Exception e) {
            XposedBridge.log("[" + cacheKey + "] translate exception => " + e.getMessage());
            return null;
        }
    }

    /** Parse kết quả từ API nội bộ */
    private static String parseLocalResult(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("translation")) {
                return obj.getString("translation");
            }
            return null;
        } catch (JSONException e) {
            XposedBridge.log("parseLocalResult error => " + e.getMessage());
            return null;
        }
    }

    /** Escape chuỗi cho JSON payload */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /** Quy tắc bỏ qua dịch */
    private static boolean isTranslationNeeded(String string) {
        if (string.matches("^\\d+([.:\\-]\\d+)*$")) {
            return false;
        }
        if (string.matches("^\\[.*]$")) {
            return false;
        }
        return true;
    }
}
