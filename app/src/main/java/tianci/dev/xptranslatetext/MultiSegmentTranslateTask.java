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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

class MultiSegmentTranslateTask extends android.os.AsyncTask<String, Void, Boolean> {

    private static final String TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml";
    private static final String API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";

    // Cache: (srcLang + tgtLang + text) -> translated
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();

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

            String cacheKey = srcLang + ":" + tgtLang + ":" + txt;
            if (translationCache.containsKey(cacheKey)) {
                seg.translatedText = translationCache.get(cacheKey);
                continue;
            }

            // Dịch theo từng dòng
            String result = translateByLines(txt, srcLang, tgtLang);
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
     * Tách text thành từng dòng, bảo vệ icon, và dịch dưới dạng mảng
     */
    private String translateByLines(String text, String src, String dst) {
        // Kiểm tra xem có xuống dòng không
        if (!text.contains("\n") && !text.contains("\r")) {
            // Chỉ có 1 dòng, dịch bình thường
            return protectAndTranslate(text, src, dst);
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
            return protectAndTranslate(text, src, dst);
        }
        
        // Chuẩn bị dữ liệu cho từng dòng
        List<String> linesToTranslate = new ArrayList<>();
        List<List<String>> lineIconContents = new ArrayList<>();
        
        for (String line : lines) {
            List<String> iconContents = new ArrayList<>();
            String protectedLine;
            
            if (line.trim().isEmpty()) {
                // Dòng trống
                protectedLine = line;
            } else {
                // Bảo vệ icon trong dòng này
                protectedLine = protectIcons(line, iconContents);
            }
            
            linesToTranslate.add(protectedLine);
            lineIconContents.add(iconContents);
        }
        
        // Debug log
        XposedBridge.log("translateByLines: splitting '" + text + "' into " + lines.length + " lines");
        for (int i = 0; i < linesToTranslate.size(); i++) {
            XposedBridge.log("Line " + i + ": '" + linesToTranslate.get(i) + "'");
        }
        
        // Dịch tất cả dòng cùng lúc
        List<String> translatedLines = translateLinesArray(linesToTranslate, src, dst);
        if (translatedLines == null) {
            XposedBridge.log("translateLinesArray returned null, fallback to single translation");
            return protectAndTranslate(text, src, dst);
        }
        
        if (translatedLines.size() != lines.length) {
            XposedBridge.log("translateLinesArray size mismatch: expected " + lines.length + ", got " + translatedLines.size());
            return protectAndTranslate(text, src, dst);
        }
        
        // Khôi phục icon và ghép lại thành text hoàn chỉnh
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < translatedLines.size(); i++) {
            String translatedLine = translatedLines.get(i);
            List<String> iconContents = lineIconContents.get(i);
            
            // Khôi phục icon
            String restoredLine = restoreIcons(translatedLine, iconContents);
            result.append(restoredLine);
            
            // Thêm xuống dòng (trừ dòng cuối cùng)
            if (i < translatedLines.size() - 1) {
                result.append(lineBreakType);
            }
        }
        
        return result.toString();
    }

    /**
     * Bảo vệ icon trong một dòng
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
     * Khôi phục icon trong một dòng đã dịch
     */
    private String restoreIcons(String translatedText, List<String> iconContents) {
        String result = translatedText;
        for (int i = 0; i < iconContents.size(); i++) {
            result = result.replace("__ICON" + i + "__", iconContents.get(i));
        }
        return result;
    }

    /**
     * Dịch mảng các dòng cùng lúc
     */
    private List<String> translateLinesArray(List<String> lines, String src, String dst) {
        try {
            URL url = new URL(TRANSLATE_URL + "?key=" + API_KEY);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json+protobuf");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setDoOutput(true);

            // Tạo mảng JSON cho payload: [["line1","line2","line3"],"src","dst"]
            StringBuilder linesArray = new StringBuilder();
            linesArray.append("[");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) linesArray.append(",");
                linesArray.append("\"").append(escapeJson(lines.get(i))).append("\"");
            }
            linesArray.append("]");

            String payload = "[[[" + linesArray.toString() + "],\"" + src + "\",\"" + dst + "\"],\"te\"]";
            
            XposedBridge.log("translateLinesArray payload: " + payload);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes("UTF-8");
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            XposedBridge.log("translateLinesArray response code: " + responseCode);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            
            String response = sb.toString();
            XposedBridge.log("translateLinesArray response: " + response);

            return parseLinesResult(response);
        } catch (Exception e) {
            XposedBridge.log("Error in translateLinesArray => " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Parse kết quả dịch mảng
     */
    private List<String> parseLinesResult(String json) {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray translatedArray = root.getJSONArray(0);
            
            List<String> result = new ArrayList<>();
            for (int i = 0; i < translatedArray.length(); i++) {
                result.add(translatedArray.getString(i));
            }
            return result;
        } catch (JSONException e) {
            XposedBridge.log("Error parsing lines translation result => " + e.getMessage());
            return null;
        }
    }

    /**
     * Dịch đơn lẻ cho trường hợp 1 dòng (fallback)
     */
    private String protectAndTranslate(String text, String src, String dst) {
        List<String> bracketsContent = new ArrayList<>();
        String protectedText = protectIcons(text, bracketsContent);

        String translated = translateOnline(protectedText, src, dst);
        if (translated == null) return null;

        return restoreIcons(translated, bracketsContent);
    }

    private String translateOnline(String text, String src, String dst) {
        try {
            URL url = new URL(TRANSLATE_URL + "?key=" + API_KEY);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json+protobuf");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setDoOutput(true);

            String payload = "[[[\"" + escapeJson(text) + "\"],\"" + src + "\",\"" + dst + "\"],\"te\"]";

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

            return parseResult(sb.toString());
        } catch (Exception e) {
            XposedBridge.log("Error in translateOnline => " + e.getMessage());
            return null;
        }
    }

    private String parseResult(String json) {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray translatedArray = root.getJSONArray(0);
            return translatedArray.getString(0);
        } catch (JSONException e) {
            XposedBridge.log("Error parsing translation result => " + e.getMessage());
            return null;
        }
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
