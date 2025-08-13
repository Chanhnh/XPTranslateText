package tianci.dev.xptranslatetext;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用來翻譯多個 Segment
 */
class MultiSegmentTranslateTask {
    private static boolean DEBUG = true;
    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newCachedThreadPool();
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor();

    // Translation API configuration
    private static final String TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml";
    private static final String API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";

    // 簡易翻譯快取: (srcLang + tgtLang + text) -> translated
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();
    private static TranslationDatabaseHelper dbHelper;

    public static void initDatabaseHelper(Context context) {
        if (dbHelper == null) {
            dbHelper = new TranslationDatabaseHelper(context.getApplicationContext());
        }
    }

    private static void log(String msg) {
        if (DEBUG) {
            XposedBridge.log(msg);
        }
    }

    public static void translateSegmentsAsync(
            final XC_MethodHook.MethodHookParam param,
            final int translationId,
            final List<Segment> segments,
            final String srcLang,
            final String tgtLang
    ) {
        TRANSLATION_EXECUTOR.submit(() -> {
            try {
            // ⏳ Delay 1 giây trước khi dịch
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

            doTranslateSegments(segments, srcLang, tgtLang);

            new Handler(Looper.getMainLooper()).post(() -> {
                // 確認 TextView 的 Tag 是否還是同一個 translationId
                Method setTagMethod = XposedHelpers.findMethodExactIfExists(param.thisObject.getClass(), "getTag", Object.class);
                if (setTagMethod != null) {
                    Object tagObj = XposedHelpers.callMethod(param.thisObject, "getTag");
                    if (!(tagObj instanceof Integer)) {
                        log("Tag mismatch => skip.");
                        return;
                    }
                    int currentTag = (Integer) tagObj;
                    if (currentTag == translationId) {
                        // 還是同一個 => 套用翻譯後結果
                        HookMain.applyTranslatedSegments(param, segments);
                    } else {
                        log("MultiSegmentTranslateTask => expired. currentTag=" + currentTag
                                + ", myId=" + translationId);
                    }
                } else {
                    //doesn't support setTag
                    HookMain.applyTranslatedSegments(param, segments);
                }
            });
        });
    }

    private static void doTranslateSegments(List<Segment> mSegments, String srcLang, String tgtLang) {
        // 逐段翻譯
        for (Segment seg : mSegments) {
            String text = seg.text;
            if (text.trim().isEmpty()) {
                seg.translatedText = text;
                continue;
            }

            // 查快取 (chỉ cache nếu text không chứa [])
            String cacheKey = srcLang + ":" + tgtLang + ":" + text;
            log(String.format("[%s] start translate", cacheKey));

            boolean shouldCache = !text.contains("[") || !text.contains("]");
            
            if (shouldCache) {
                log(String.format("[%s] checking cache", cacheKey));
                if (translationCache.containsKey(cacheKey)) {
                    seg.translatedText = translationCache.get(cacheKey);
                    log(String.format("[%s] hit from cache", cacheKey));
                    continue;
                }

                log(String.format("[%s] checking sqlite", cacheKey));
                String dbResult = getTranslationFromDatabase(cacheKey);
                if (dbResult != null) {
                    seg.translatedText = dbResult;
                    log(String.format("[%s] hit from sqlite => %s", cacheKey, dbResult));
                    translationCache.put(cacheKey, dbResult);
                    continue;
                }
            } else {
                log(String.format("[%s] skip cache (contains [])", cacheKey));
            }

            if (!isTranslationNeeded(text)) {
                seg.translatedText = text;
                log(String.format("[%s] not need translate", cacheKey));
                continue;
            }

            // Dịch theo từng dòng với API mới
            log(String.format("[%s] translate start by new api", cacheKey));
            String result = translateByLines(text, srcLang, tgtLang, cacheKey);
            log(String.format("[%s] translate end by new api => %s", cacheKey, result));

            if (result == null) {
                seg.translatedText = text; // 翻譯失敗 => 用原文
            } else {
                seg.translatedText = result;
                if (shouldCache) {
                    translationCache.put(cacheKey, result);
                    putTranslationToDatabase(cacheKey, result);
                }
            }
        }
    }

    /**
     * Dịch từng dòng riêng biệt để bảo toàn format
     */
    private static String translateByLines(String text, String src, String dst, String cacheKey) {
        // Kiểm tra xem có xuống dòng không
        if (!text.contains("\n") && !text.contains("\r")) {
            // Chỉ có 1 dòng, dịch bình thường
            return protectAndTranslate(text, src, dst, cacheKey);
        }
                
        // Tách text thành từng dòng và giữ lại thông tin về ký tự xuống dòng
        String[] lines;
        String lineBreakType = "\n"; // default
                
        if (text.contains("\r\n")) {
            lines = text.split("\r\n", -1);
            lineBreakType = "\r\n";
        } else if (text.contains("\n")) {
            lines = text.split("\n", -1);
            lineBreakType = "\n";
        } else if (text.contains("\r")) {
            lines = text.split("\r", -1);
            lineBreakType = "\r";
        } else {
            lines = new String[]{text};
        }
                
        // Nếu sau khi split chỉ có 1 phần tử, dịch bình thường
        if (lines.length <= 1) {
            return protectAndTranslate(text, src, dst, cacheKey);
        }
                
        log("translateByLines: splitting '" + text + "' into " + lines.length + " lines");
                
        // Dịch từng dòng riêng biệt
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String translatedLine;
                        
            if (line.trim().isEmpty()) {
                // Dòng trống giữ nguyên
                translatedLine = line;
            } else {
                // Dịch dòng này
                translatedLine = protectAndTranslate(line, src, dst, cacheKey);
                if (translatedLine == null) {
                    translatedLine = line; // fallback
                }
            }
                        
            result.append(translatedLine);
                        
            // Thêm xuống dòng (trừ dòng cuối cùng)
            if (i < lines.length - 1) {
                result.append(lineBreakType);
            }
        }
                
        return result.toString();
    }

    /**
     * Bảo vệ icon trong một dòng
     */
    private static String protectIcons(String text, List<String> iconContents) {
        Matcher m = Pattern.compile("\\[[^\\]]*]").matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find()) {
            iconContents.add(m.group());
            m.appendReplacement(sb, "__ICON" + idx + "__");
            idx++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Khôi phục icon trong một dòng đã dịch
     */
    private static String restoreIcons(String translatedText, List<String> iconContents) {
        String result = translatedText;
        for (int i = 0; i < iconContents.size(); i++) {
            result = result.replace("__ICON" + i + "__", iconContents.get(i));
        }
        return result;
    }

    /**
     * Dịch đơn lẻ cho trường hợp 1 dòng (fallback)
     */
    private static String protectAndTranslate(String text, String src, String dst, String cacheKey) {
        List<String> bracketsContent = new ArrayList<>();
        String protectedText = protectIcons(text, bracketsContent);

        String translated = translateOnline(protectedText, src, dst, cacheKey);
        if (translated == null) return null;

        return restoreIcons(translated, bracketsContent);
    }

    private static String translateOnline(String text, String src, String dst, String cacheKey) {
            try {
            // Tách số và chữ: ví dụ "302.5万" → ["302.5", "万"]
            String numberPart = "";
            String textPart = text;

            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(.*)$").matcher(text.trim());
            if (m.find()) {
            numberPart = m.group(1);  // phần số
            textPart = m.group(2);    // phần chữ
            }

            // Nếu phần chữ rỗng => không cần dịch
            if (textPart.isEmpty()) {
                return text;
            }
            URL url = new URL(TRANSLATE_URL + "?key=" + API_KEY);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json+protobuf");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);

            String payload = "[[[\"" + escapeJson(text) + "\"],\"" + src + "\",\"" + dst + "\"],\"te\"]";

            log(String.format("[%s] request sent, awaiting response from new api...", cacheKey));
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes("UTF-8");
                os.write(input, 0, input.length);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            //return parseResult(sb.toString(), cacheKey);
            String translatedTextPart = parseResult(sb.toString(), cacheKey);

        // Ghép lại: phần số + phần dịch
            return numberPart + (translatedTextPart != null ? translatedTextPart : textPart);
        } catch (Exception e) {
            log(String.format("[%s] translate exception in new api => ", cacheKey) + e.getMessage());
            return null;
        }
    }

    private static String parseResult(String json, String cacheKey) {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray translatedArray = root.getJSONArray(0);
            String result = translatedArray.getString(0);
                        
            // Decode HTML entities
            result = decodeHtmlEntities(result);
                        
            return result;
        } catch (JSONException e) {
            log(String.format("[%s] parsing new api exception response => %s", cacheKey, e.getMessage()));
            return null;
        }
    }

    /**
     * Decode HTML entities trong kết quả dịch
     */
    private static String decodeHtmlEntities(String text) {
        if (text == null) return null;
                
        return text.replace("&quot;", "\"")
                  .replace("&amp;", "&")
                  .replace("&lt;", "<")
                  .replace("&gt;", ">")
                  .replace("&apos;", "'")
                  .replace("&#39;", "'")
                  .replace("&nbsp;", " ")
                  // Decode numeric entities
                  .replaceAll("&#(\\d+);", "")  // Remove numeric entities for now
                  .replaceAll("&#x([0-9A-Fa-f]+);", ""); // Remove hex entities for now
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
               .replace("\"", "\\\"")
               .replace("\n", "\\n")
               .replace("\r", "\\r");
    }

    private static boolean isTranslationNeeded(String string) {
        // Regex để nhận diện "emoji + [mô tả icon]" và bỏ qua dịch
        Pattern onlyIconPattern = Pattern.compile("^\\p{So}*\\[[^\\]]*]$");
        
        // Nếu chỉ là emoji + [mô tả icon] thì không cần dịch
        if (onlyIconPattern.matcher(string.trim()).matches()) {
            return false;
        }

        // 純數字
        if (string.matches("^\\d+$")) {
            return false;
        }

        // 座標
        if (string.matches("^\\d{1,3}\\.\\d+$")) {
            return false;
        }

        return true;
    }

    private static String getTranslationFromDatabase(String cacheKey) {
        if (dbHelper == null) return null;
        try {
            return DB_EXECUTOR.submit(() -> dbHelper.getTranslation(cacheKey))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log("DB fetch error: " + e);
            return null;
        }
    }

    private static void putTranslationToDatabase(String cacheKey, String translatedText) {
        if (dbHelper == null) return;
        try {
            DB_EXECUTOR.submit(() -> {
                dbHelper.putTranslation(cacheKey, translatedText);
                return null;
            }).get(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log("DB put error: " + e);
        }
    }

    public static void translateFromJs(WebView webView, String requestId, String text, String srcLang, String tgtLang) {
        String cacheKey = srcLang + ":" + tgtLang + ":" + text;
        log(String.format("[%s] start translate", cacheKey));

        // web translate: chỉ cache nếu không chứa []
        boolean shouldCache = !text.contains("[") || !text.contains("]");
        String result = null;
        
        log(String.format("[%s] translate start by new api", cacheKey));
        result = translateByLines(text, srcLang, tgtLang, cacheKey);
        log(String.format("[%s] translate end by new api => %s", cacheKey, result));

        if (result == null) {
            webView.post(() -> webView.evaluateJavascript(String.format("javascript:onXPTranslateCompleted(\'%s\',\'%s\')", requestId, text), null));
        } else {
            if (shouldCache) {
                translationCache.put(cacheKey, result);
            }
            String finalResult = result;
            webView.post(() -> webView.evaluateJavascript(String.format("javascript:onXPTranslateCompleted(\'%s\',\'%s\')", requestId, finalResult), null));
        }
    }
}
