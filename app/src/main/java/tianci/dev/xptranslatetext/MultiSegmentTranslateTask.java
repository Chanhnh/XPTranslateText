package tianci.dev.xptranslatetext;

import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

class MultiSegmentTranslateTask extends android.os.AsyncTask<String, Void, Boolean> {

    private static final boolean DEBUG = true;
    
    // Chỉ sử dụng API chính thức - chất lượng cao
    private static final String TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml";
    private static final String API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";

    // Cache: (srcLang + tgtLang + text) -> translated
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();
    
    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newCachedThreadPool();

    private final XC_MethodHook.MethodHookParam mParam;
    private final int mTranslationId;
    private final List<Segment> mSegments;

    public MultiSegmentTranslateTask(XC_MethodHook.MethodHookParam param,
                                     int translationId,
                                     List<Segment> segments) {
        this.mParam = param;
        this.mTranslationId = translationId;
        this.mSegments = segments;
    }
    
    private static void log(String msg) {
        if (DEBUG) {
            XposedBridge.log(msg);
        }
    }

    @Override
    protected Boolean doInBackground(String... params) {
        if (params.length < 2) return false;
        String srcLang = params[0];
        String tgtLang = params[1];

        // Regex để nhận diện "emoji + [mô tả icon]" và bỏ qua dịch
        Pattern onlyIconPattern = Pattern.compile("^\\p{So}*\\[[^\\]]*]$");

        for (Segment seg : mSegments) {
            String txt = seg.text;
            if (txt.trim().isEmpty()) {
                seg.translatedText = txt;
                continue;
            }

            // Nếu chỉ là emoji + [mô tả icon] thì giữ nguyên
            if (onlyIconPattern.matcher(txt.trim()).matches()) {
                seg.translatedText = txt;
                continue;
            }
            
            // Bỏ qua những text không cần dịch
            if (!isTranslationNeeded(txt)) {
                seg.translatedText = txt;
                continue;
            }

            String cacheKey = srcLang + ":" + tgtLang + ":" + txt;
            log(String.format("[%s] start translate", cacheKey));
            
            if (translationCache.containsKey(cacheKey)) {
                seg.translatedText = translationCache.get(cacheKey);
                log(String.format("[%s] hit from cache", cacheKey));
                continue;
            }

            // Dịch text (có xử lý multi-line)
            String result = translateText(txt, srcLang, tgtLang, cacheKey);
            if (result == null) {
                seg.translatedText = txt; // fallback
            } else {
                seg.translatedText = result;
                translationCache.put(cacheKey, result);
            }
        }
        return true;
    }

    /**
     * Main translate function - xử lý cả single line và multi-line
     */
    private String translateText(String text, String src, String dst, String cacheKey) {
        // Kiểm tra xem có xuống dòng không
        if (!text.contains("\n") && !text.contains("\r")) {
            // Single line - dịch trực tiếp
            return translateSingleLine(text, src, dst, cacheKey);
        }
        
        // Multi-line - xử lý từng dòng
        return translateMultiLine(text, src, dst, cacheKey);
    }
    
    /**
     * Dịch single line bằng API chính thức
     */
    private String translateSingleLine(String text, String src, String dst, String cacheKey) {
        // Bảo vệ icon trước khi dịch
        List<String> iconContents = new ArrayList<>();
        String protectedText = protectIcons(text, iconContents);
        
        log(String.format("[%s] translate start by official api", cacheKey));
        String result = translateByOfficialApi(protectedText, src, dst, cacheKey);
        log(String.format("[%s] translate end by official api => %s", cacheKey, result));
        
        if (result == null) return null;
        
        // Khôi phục icon
        return restoreIcons(result, iconContents);
    }
    
    /**
     * Dịch multi-line - giữ nguyên format
     */
    private String translateMultiLine(String text, String src, String dst, String cacheKey) {
        // Tách text thành từng dòng
        String[] lines;
        String lineBreakType = "\n";
        
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
        
        if (lines.length <= 1) {
            return translateSingleLine(text, src, dst, cacheKey);
        }
        
        log(String.format("[%s] splitting into %d lines", cacheKey, lines.length));
        
        // Dịch từng dòng
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String translatedLine;
            
            if (line.trim().isEmpty()) {
                translatedLine = line;
            } else {
                String lineCacheKey = cacheKey + ":line" + i;
                translatedLine = translateSingleLine(line, src, dst, lineCacheKey);
                if (translatedLine == null) {
                    translatedLine = line;
                }
            }
            
            result.append(translatedLine);
            if (i < lines.length - 1) {
                result.append(lineBreakType);
            }
        }
        
        return result.toString();
    }

    /**
     * API chính thức với tối ưu tốc độ
     */
    private String translateByOfficialApi(String text, String src, String dst, String cacheKey) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TRANSLATE_URL + "?key=" + API_KEY);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json+protobuf");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Connection", "keep-alive"); // Tái sử dụng kết nối
            conn.setRequestProperty("Accept-Encoding", "gzip"); // Nén dữ liệu
            
            // Tối ưu timeout cho tốc độ
            conn.setConnectTimeout(3000); // 3s connect
            conn.setReadTimeout(5000); // 5s read
            conn.setDoOutput(true);

            String payload = "[[[\"" + escapeJson(text) + "\"],\"" + src + "\",\"" + dst + "\"],\"te\"]";

            log(String.format("[%s] sending request to official api", cacheKey));
            
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes("UTF-8");
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log(String.format("[%s] official api response code: %d", cacheKey, responseCode));
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            return parseOfficialApiResult(sb.toString());
        } catch (Exception e) {
            log(String.format("[%s] official api error => %s", cacheKey, e.getMessage()));
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Parse Official API response
     */
    private String parseOfficialApiResult(String json) {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray translatedArray = root.getJSONArray(0);
            String result = translatedArray.getString(0);
            return decodeHtmlEntities(result);
        } catch (JSONException e) {
            log("Parse official api error => " + e.getMessage());
            return null;
        }
    }

    /**
     * Kiểm tra xem text có cần dịch không
     */
    private boolean isTranslationNeeded(String text) {
        // Bỏ qua số thuần túy
        if (text.matches("^\\d+$")) {
            return false;
        }
        
        // Bỏ qua tọa độ
        if (text.matches("^\\d{1,3}\\.\\d+$")) {
            return false;
        }
        
        // Bỏ qua URL
        if (text.matches("^https?://.*")) {
            return false;
        }
        
        return true;
    }

    /**
     * Bảo vệ icon trong text
     */
    private String protectIcons(String text, List<String> iconContents) {
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
     * Khôi phục icon trong text đã dịch
     */
    private String restoreIcons(String translatedText, List<String> iconContents) {
        String result = translatedText;
        for (int i = 0; i < iconContents.size(); i++) {
            result = result.replace("__ICON" + i + "__", iconContents.get(i));
        }
        return result;
    }

    /**
     * Decode HTML entities
     */
    private String decodeHtmlEntities(String text) {
        if (text == null) return null;
        
        return text.replace("&quot;", "\"")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&apos;", "'")
                   .replace("&#39;", "'")
                   .replace("&nbsp;", " ");
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    protected void onPostExecute(Boolean success) {
        if (!success) {
            XposedBridge.log("MultiSegmentTranslateTask => failed.");
            return;
        }

        TextView tv = (TextView) mParam.thisObject;
        Object tagObj = tv.getTag();
        if (!(tagObj instanceof Integer)) {
            XposedBridge.log("Tag mismatch => skip.");
            return;
        }
        int currentTag = (Integer) tagObj;
        if (currentTag == mTranslationId) {
            HookMain.applyTranslatedSegments(mParam, mSegments);
        } else {
            XposedBridge.log("MultiSegmentTranslateTask => expired. currentTag=" + currentTag
                    + ", myId=" + mTranslationId);
        }
    }
}
