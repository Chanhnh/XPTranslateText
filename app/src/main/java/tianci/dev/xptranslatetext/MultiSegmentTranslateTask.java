package tianci.dev.xptranslatetext;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

class MultiSegmentTranslateTask {
    
    private static final String TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml";
    private static final String API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";
    
    // Thread pool tối ưu cho việc dịch
    private static final ExecutorService TRANSLATION_EXECUTOR = 
        Executors.newFixedThreadPool(Math.min(Runtime.getRuntime().availableProcessors(), 4));
    
    // Cache: (srcLang + tgtLang + text) -> translated
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();
    
    // Pattern để nhận diện emoji và icon (compile một lần)
    private static final Pattern ONLY_ICON_PATTERN = Pattern.compile("^\\p{So}*\\[[^\\]]*]$");
    private static final Pattern ICON_PATTERN = Pattern.compile("\\[[^\\]]*]");
    
    // Pattern để kiểm tra text không cần dịch
    private static final Pattern NO_TRANSLATE_PATTERNS = Pattern.compile(
        "^(\\d+|\\d{1,3}\\.\\d+|[\\p{So}\\s]*|[\\p{P}\\s]*)$"
    );
    
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
    
    /**
     * Bắt đầu quá trình dịch bất đồng bộ
     */
    public void executeAsync(String srcLang, String tgtLang) {
        CompletableFuture.supplyAsync(() -> doInBackground(srcLang, tgtLang), TRANSLATION_EXECUTOR)
            .orTimeout(30, TimeUnit.SECONDS)
            .whenComplete((success, throwable) -> {
                if (throwable != null) {
                    XposedBridge.log("Translation failed: " + throwable.getMessage());
                    success = false;
                }
                onPostExecute(success);
            });
    }
    
    private Boolean doInBackground(String srcLang, String tgtLang) {
        // Lọc các segment cần dịch
        List<Segment> needTranslation = mSegments.stream()
            .filter(seg -> needsTranslation(seg.text))
            .collect(Collectors.toList());
        
        if (needTranslation.isEmpty()) {
            // Tất cả đều không cần dịch
            for (Segment seg : mSegments) {
                seg.translatedText = seg.text;
            }
            return true;
        }
        
        // Batch translate cho hiệu suất tốt hơn
        return batchTranslate(needTranslation, srcLang, tgtLang);
    }
    
    /**
     * Kiểm tra text có cần dịch không
     */
    private boolean needsTranslation(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = text.trim();
        
        // Chỉ là emoji + [mô tả icon]
        if (ONLY_ICON_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        
        // Chỉ là số, dấu chấm hoặc ký tự đặc biệt
        if (NO_TRANSLATE_PATTERNS.matcher(trimmed).matches()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Dịch theo batch để tối ưu performance
     */
    private Boolean batchTranslate(List<Segment> segments, String srcLang, String tgtLang) {
        // Nhóm các segment theo độ dài để batch hiệu quả
        List<List<Segment>> batches = createBatches(segments, 10); // Tối đa 10 segment/batch
        
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        
        for (List<Segment> batch : batches) {
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> 
                processBatch(batch, srcLang, tgtLang), TRANSLATION_EXECUTOR);
            futures.add(future);
        }
        
        // Chờ tất cả batch hoàn thành
        try {
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            allOf.get(25, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            XposedBridge.log("Batch translation failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Tạo các batch từ danh sách segment
     */
    private List<List<Segment>> createBatches(List<Segment> segments, int batchSize) {
        List<List<Segment>> batches = new ArrayList<>();
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            batches.add(segments.subList(i, end));
        }
        return batches;
    }
    
    /**
     * Xử lý một batch segment
     */
    private Boolean processBatch(List<Segment> batch, String srcLang, String tgtLang) {
        for (Segment seg : batch) {
            String txt = seg.text;
            
            if (!needsTranslation(txt)) {
                seg.translatedText = txt;
                continue;
            }
            
            String cacheKey = srcLang + ":" + tgtLang + ":" + txt;
            if (translationCache.containsKey(cacheKey)) {
                seg.translatedText = translationCache.get(cacheKey);
                continue;
            }
            
            // Dịch theo từng dòng với tối ưu hóa
            String result = optimizedTranslateByLines(txt, srcLang, tgtLang);
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
     * Dịch từng dòng với tối ưu hóa
     */
    private String optimizedTranslateByLines(String text, String src, String dst) {
        // Kiểm tra nhanh xem có xuống dòng không
        boolean hasNewlines = text.contains("\n") || text.contains("\r");
        
        if (!hasNewlines) {
            return protectAndTranslate(text, src, dst);
        }
        
        // Xử lý nhiều dòng - tối ưu hóa split
        String[] lines = splitLines(text);
        if (lines.length <= 1) {
            return protectAndTranslate(text, src, dst);
        }
        
        // Dịch song song các dòng không rỗng
        List<CompletableFuture<String>> futures = new ArrayList<>();
        List<Integer> nonEmptyIndices = new ArrayList<>();
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.trim().isEmpty()) {
                nonEmptyIndices.add(i);
                final int index = i;
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    String result = protectAndTranslate(line, src, dst);
                    return result != null ? result : line;
                }, TRANSLATION_EXECUTOR);
                futures.add(future);
            }
        }
        
        try {
            // Chờ tất cả dịch xong với timeout
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            allOf.get(10, TimeUnit.SECONDS);
            
            // Ghép kết quả
            String[] translatedLines = lines.clone();
            for (int i = 0; i < futures.size(); i++) {
                int originalIndex = nonEmptyIndices.get(i);
                translatedLines[originalIndex] = futures.get(i).get();
            }
            
            return joinLines(translatedLines, detectLineBreakType(text));
            
        } catch (Exception e) {
            XposedBridge.log("Parallel line translation failed: " + e.getMessage());
            return protectAndTranslate(text, src, dst); // fallback
        }
    }
    
    /**
     * Tối ưu split lines
     */
    private String[] splitLines(String text) {
        if (text.contains("\r\n")) {
            return text.split("\r\n", -1);
        } else if (text.contains("\n")) {
            return text.split("\n", -1);
        } else if (text.contains("\r")) {
            return text.split("\r", -1);
        }
        return new String[]{text};
    }
    
    /**
     * Detect line break type
     */
    private String detectLineBreakType(String text) {
        if (text.contains("\r\n")) return "\r\n";
        if (text.contains("\n")) return "\n";
        if (text.contains("\r")) return "\r";
        return "\n";
    }
    
    /**
     * Join lines back
     */
    private String joinLines(String[] lines, String lineBreakType) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            result.append(lines[i]);
            if (i < lines.length - 1) {
                result.append(lineBreakType);
            }
        }
        return result.toString();
    }
    
    /**
     * Bảo vệ icon trong một dòng - tối ưu hóa
     */
    private String protectIcons(String text, List<String> iconContents) {
        Matcher m = ICON_PATTERN.matcher(text);
        if (!m.find()) {
            return text; // Không có icon, return ngay
        }
        
        m.reset();
        StringBuilder sb = new StringBuilder();
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
     * Khôi phục icon - tối ưu hóa
     */
    private String restoreIcons(String translatedText, List<String> iconContents) {
        if (iconContents.isEmpty()) {
            return translatedText;
        }
        
        String result = translatedText;
        for (int i = 0; i < iconContents.size(); i++) {
            result = result.replace("__ICON" + i + "__", iconContents.get(i));
        }
        return result;
    }
    
    /**
     * Dịch đơn lẻ với connection pooling
     */
    private String protectAndTranslate(String text, String src, String dst) {
        List<String> bracketsContent = new ArrayList<>();
        String protectedText = protectIcons(text, bracketsContent);
        
        String translated = translateOnlineOptimized(protectedText, src, dst);
        if (translated == null) return null;
        
        return restoreIcons(translated, bracketsContent);
    }
    
    /**
     * Tối ưu hóa connection và request
     */
    private String translateOnlineOptimized(String text, String src, String dst) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TRANSLATE_URL + "?key=" + API_KEY);
            conn = (HttpURLConnection) url.openConnection();
            
            // Tối ưu connection settings
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json+protobuf");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            
            String payload = "[[[\"" + escapeJsonOptimized(text) + 
                           "\"],\"" + src + "\",\"" + dst + "\"],\"te\"]";
            
            // Optimized write
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes("UTF-8");
                os.write(input, 0, input.length);
                os.flush();
            }
            
            // Check response code first
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                XposedBridge.log("HTTP Error: " + responseCode);
                return null;
            }
            
            // Optimized read with StringBuilder capacity
            StringBuilder sb = new StringBuilder(text.length() * 2);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"), 8192)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            
            return parseResultOptimized(sb.toString());
            
        } catch (Exception e) {
            XposedBridge.log("Error in translateOnlineOptimized => " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    /**
     * Optimized JSON parsing
     */
    private String parseResultOptimized(String json) {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray translatedArray = root.getJSONArray(0);
            String result = translatedArray.getString(0);
            
            // Optimized HTML entity decoding
            return decodeHtmlEntitiesOptimized(result);
        } catch (JSONException e) {
            XposedBridge.log("Error parsing translation result => " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Optimized HTML entity decoding
     */
    private String decodeHtmlEntitiesOptimized(String text) {
        if (text == null || text.indexOf('&') == -1) {
            return text;
        }
        
        return text.replace("&quot;", "\"")
                  .replace("&amp;", "&")
                  .replace("&lt;", "<")
                  .replace("&gt;", ">")
                  .replace("&apos;", "'")
                  .replace("&#39;", "'")
                  .replace("&nbsp;", " ");
    }
    
    /**
     * Optimized JSON escaping
     */
    private String escapeJsonOptimized(String s) {
        if (s.indexOf('\\') == -1 && s.indexOf('"') == -1 && 
            s.indexOf('\n') == -1 && s.indexOf('\r') == -1) {
            return s;
        }
        
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
    
    private void onPostExecute(Boolean success) {
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
    
    /**
     * Cleanup resources when app terminates
     */
    public static void shutdown() {
        TRANSLATION_EXECUTOR.shutdown();
        try {
            if (!TRANSLATION_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                TRANSLATION_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            TRANSLATION_EXECUTOR.shutdownNow();
        }
    }
}
