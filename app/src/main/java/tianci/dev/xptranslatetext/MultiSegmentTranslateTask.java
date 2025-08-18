package tianci.dev.xptranslatetext;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;

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
 * 用來翻譯多個 Segment
 */
class MultiSegmentTranslateTask {
    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newFixedThreadPool(20);

    // API duy nhất
    private static final String TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml";
    private static final String API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";

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
                // 確認 TextView 的 Tag 是否還是同一個 translationId
                TextView tv = (TextView) param.thisObject;
                Object tagObj = tv.getTag();
                if (!(tagObj instanceof Integer)) {
                    XposedBridge.log("Tag mismatch => skip.");
                    return;
                }
                int currentTag = (Integer) tagObj;
                if (currentTag == translationId) {
                    // 套用翻譯後結果
                    HookMain.applyTranslatedSegments(param, segments);
                } else {
                    XposedBridge.log("MultiSegmentTranslateTask => expired. currentTag=" + currentTag
                            + ", myId=" + translationId);
                }
            });
        });
    }

    private static void doTranslateSegments(List<Segment> mSegments, String srcLang, String tgtLang) {
        // 逐段翻譯
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
                String translated = translateByLines(raw, srcLang, tgtLang, cacheKey);
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

            // 🌐 Dịch bình thường (giữ định dạng theo dòng)
            String result = translateByLines(text, srcLang, tgtLang, cacheKey);
            if (result == null) {
                seg.translatedText = text; // fallback giữ nguyên
            } else {
                seg.translatedText = result;
                translationCache.put(cacheKey, result);
            }
        }
    }

    /** Dịch từng dòng riêng biệt để bảo toàn format xuống dòng */
    private static String translateByLines(String text, String src, String dst, String cacheKey) {
        // Nếu không có xuống dòng → dịch một phát
        if (!text.contains("\n") && !text.contains("\r")) {
            return translateOnline(text, src, dst, cacheKey);
        }

        String[] lines;
        String lineBreakType = "\n";
        if (text.contains("\r\n")) {
            lines = text.split("\r\n", -1);
            lineBreakType = "\r\n";
        } else if (text.contains("\n")) {
            lines = text.split("\n", -1);
            lineBreakType = "\n";
        } else { // chỉ \r
            lines = text.split("\r", -1);
            lineBreakType = "\r";
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String translatedLine;
            if (line.trim().isEmpty()) {
                translatedLine = line; // giữ nguyên dòng trống
            } else {
                translatedLine = translateOnline(line, src, dst, cacheKey);
                if (translatedLine == null) translatedLine = line; // fallback
            }
            result.append(translatedLine);
            if (i < lines.length - 1) result.append(lineBreakType);
        }
        return result.toString();
    }

    /** Gọi API translate-pa (chỉ 1 API duy nhất) */
    private static String translateOnline(String text, String src, String dst, String cacheKey) {
        try {
            URL url = new URL(TRANSLATE_URL + "?key=" + API_KEY);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json+protobuf");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setDoOutput(true);

            // Payload kiểu: [[["text"],"src","dst"],"te"]
            String payload = "[[[\"" + escapeJson(text) + "\"],\"" + src + "\",\"" + dst + "\"],\"te\"]";

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

            return parseResult(sb.toString());
        } catch (Exception e) {
            XposedBridge.log("[" + cacheKey + "] translate exception => " + e.getMessage());
            return null;
        }
    }

    /** Parse kết quả từ API mới */
    private static String parseResult(String json) {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray translatedArray = root.getJSONArray(0);
            String result = translatedArray.getString(0);
            return decodeHtmlEntities(result);
        } catch (JSONException e) {
            XposedBridge.log("parseResult error => " + e.getMessage());
            return null;
        }
    }

    /** Decode HTML entities trong kết quả dịch */
    private static String decodeHtmlEntities(String text) {
        if (text == null) return null;
        return text.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
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
        // Thủ công: 万 -> vạn, 亿 -> tỷ
        if (string.matches("^[0-9.]+万$")) {
            return string.replaceAll("万$", " vạn");
        }
        if (string.matches("^[0-9.]+亿$")) {
            return string.replaceAll("亿$", " tỷ");
        }
        return true;
    }
}
