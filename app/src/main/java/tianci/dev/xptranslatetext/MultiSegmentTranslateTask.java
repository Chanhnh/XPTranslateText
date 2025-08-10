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

            // Bảo vệ icon + dịch
            String result = protectAndTranslate(txt, srcLang, tgtLang);
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
     * Bảo vệ phần [mô tả icon] trước khi dịch, khôi phục sau khi dịch
     */
    private String protectAndTranslate(String text, String src, String dst) {
        // Bảo vệ xuống dòng
        text = text.replace("\n", "\uE000"); // Placeholder newline
        
        List<String> bracketsContent = new ArrayList<>();
        Matcher m = Pattern.compile("\\[[^\\]]*]").matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find()) {
            bracketsContent.add(m.group());
            m.appendReplacement(sb, "__ICON" + idx + "__");
            idx++;
        }
        m.appendTail(sb);
        String protectedText = sb.toString();

        String translated = translateOnline(protectedText, src, dst);
        if (translated == null) return null;

        // Khôi phục phần [ ... ]
        for (int i = 0; i < bracketsContent.size(); i++) {
            translated = translated.replace("__ICON" + i + "__", bracketsContent.get(i));
        }
        
        translated = translated.replace("\uE000", "\n");
        return translated;
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
            return translatedArray.getString(0); // lấy "translated text"
        } catch (JSONException e) {
            XposedBridge.log("Error parsing translation result => " + e.getMessage());
            return null;
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
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
