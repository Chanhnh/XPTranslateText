package tianci.dev.xptranslatetext.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import tianci.dev.xptranslatetext.R;
import tianci.dev.xptranslatetext.util.ModelInfoUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * Foreground service that starts a minimal HTTPS server on 127.0.0.1:18181.
 * Route: /translate?src=xx&dst=yy&q=...
 * - When src=auto, use ML Kit Language ID for detection.
 */
public class LocalTranslationService extends Service {

    public static final String ACTION_START = "tianci.dev.xptranslatetext.action.START";
    public static final String ACTION_STOP = "tianci.dev.xptranslatetext.action.STOP";

    public static final int PORT = 18181;
    private static final String CHANNEL_ID = "local_translation_channel";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private ExecutorService clientExecutor;
    private Thread serverThread;

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        int cores = Runtime.getRuntime().availableProcessors();
        int coreThreads = Math.max(4, cores);
        int maxThreads = Math.min(64, Math.max(16, cores * 8));
        clientExecutor = new ThreadPoolExecutor(
                coreThreads,
                maxThreads,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "LocalTrans-Worker");
                    t.setDaemon(true);
                    return t;
                }
        );
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!RUNNING.get()) {
            startForeground(1, buildNotification());
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        if (clientExecutor != null) clientExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Local Translation",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("XPTranslateText Local Translation");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.server_running, PORT))
                .build();
    }

    private void startServer() {
        if (RUNNING.get()) return;
        RUNNING.set(true);
        serverThread = new Thread(() -> {
            try {
                SSLServerSocketFactory sslFactory = buildServerSslSocketFactory();
                if (sslFactory != null) {
                    try {
                        serverSocket = sslFactory.createServerSocket(PORT, 128, InetAddress.getByName("127.0.0.1"));
                        if (serverSocket instanceof SSLServerSocket) {
                            SSLServerSocket sslServerSocket = (SSLServerSocket) serverSocket;
                            try {
                                sslServerSocket.setUseClientMode(false);
                                sslServerSocket.setNeedClientAuth(false);
                                sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});                            } catch (Throwable ignored) {}
                        }
                        Log.i("LocalTranslation", "HTTPS server started on 127.0.0.1:" + PORT);
                    } catch (Throwable t) {
                        Log.e("LocalTranslation", "Failed to start HTTPS server: " + t);
                        // If HTTPS fails, do NOT fallback to HTTP to avoid cleartext policy issues.
                        throw t;
                    }
                } else {
                    // No key material => do not start to avoid cleartext
                    throw new IOException("No local HTTPS key material (PEM) available");
                }
                while (RUNNING.get()) {
                    final Socket socket = serverSocket.accept();
                    try {
                        clientExecutor.execute(() -> handleClient(socket));
                    } catch (RejectedExecutionException rex) {
                        try (OutputStream os = socket.getOutputStream()) {
                            respond(os, 503, json("error", "server busy"));
                        } catch (Throwable ignored) {
                        } finally {
                            try { socket.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (IOException e) {
                Log.e("LocalTranslation", "Server error: " + e.getMessage());
            } finally {
                RUNNING.set(false);
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (IOException ignored) {}
                }
            }
        }, "LocalTranslationServer");
        serverThread.start();
    }

    private void stopServer() {
        RUNNING.set(false);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleClient(Socket socket) {
        try (InputStream is = socket.getInputStream(); OutputStream os = socket.getOutputStream()) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

            String requestLine = br.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                respond(os, 400, json("error", "bad request"));
                return;
            }

            // Only handle GET routes.
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(os, 400, json("error", "bad request"));
                return;
            }
            String path = parts[1];

            // Consume request headers until an empty line.
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) { /* skip */ }

            if (path.startsWith("/health")) {
                respond(os, 200, json("status", "ok"));
                return;
            }

            if (!path.startsWith("/translate")) {
                respond(os, 404, json("error", "not found"));
                return;
            }

            Map<String, String> query = parseQuery(path);
            String text = query.get("q");
            String src = query.get("src");
            String dst = query.get("dst");

            if (text == null || text.isEmpty()) {
                respond(os, 400, json("error", "q required"));
                return;
            }
            if (dst == null || dst.isEmpty()) {
                // Read target language from shared preferences.
                SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
                dst = sp.getString("target_lang", "zh-TW");
            }
            if (src == null || src.isEmpty()) {
                // Read source language from shared preferences.
                SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
                src = sp.getString("source_lang", "auto");
            }

            // Auto language identification when src=auto.
            if ("auto".equalsIgnoreCase(src)) {
                LanguageIdentifier idClient = LanguageIdentification.getClient(
                        new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
                );
                try {
                    String tag = Tasks.await(idClient.identifyLanguage(text));
                    if (tag == null || "und".equalsIgnoreCase(tag)) {
                        src = "en"; // Fallback when detection fails.
                    } else {
                        src = tag;
                    }
                } catch (Exception e) {
                    src = "en";
                } finally {
                    try { idClient.close(); } catch (Throwable ignored) {}
                }
            }

            String mlSrc = normalizeToMlkitCode(src);
            String mlDst = normalizeToMlkitCode(dst);
            if (mlSrc == null || mlDst == null) {
                respond(os, 400, json("error", "unsupported language"));
                return;
            }

            Translator translator = null;
            try {
                translator = createTranslator(mlSrc, mlDst);
                // Download model if needed.
                DownloadConditions cond = new DownloadConditions.Builder().build();
                Tasks.await(translator.downloadModelIfNeeded(cond));

                // Record last used timestamps keyed by language code.
                try {
                    ModelInfoUtil.markModelUsed(this, mlSrc);
                    ModelInfoUtil.markModelUsed(this, mlDst);
                } catch (Throwable ignored) { }

                String translated = Tasks.await(translator.translate(text));
                // Convert simplified Chinese output to Traditional when requested.
                if (isTraditionalChinese(dst)) {
                    translated = toTraditionalChinese(translated);
                }
                String payload = "{\"code\":0,\"text\":" + jsonString(translated) + "}";
                respond(os, 200, payload);
            } catch (Exception e) {
                respond(os, 500, json("error", e.getMessage() == null ? "translate failed" : e.getMessage()));
            } finally {
                if (translator != null) {
                    try { translator.close(); } catch (Throwable ignored) {}
                }
            }

        } catch (IOException e) {
            // ignore per-connection errors
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Build SSLServerSocketFactory using only unencrypted PKCS#8 private key (PEM) + X.509 certificate (PEM).
     *
     * Expected locations (either pair):
     * - assets/local_https_server.key + assets/local_https_server.crt
     * - assets/local_https_server.key + assets/local_https_server.crt
     *
     * To avoid cleartext, this service does not fallback to HTTP; missing materials cause startup failure.
     */
    @Nullable
    private SSLServerSocketFactory buildServerSslSocketFactory() {
        // 僅走 PEM 流程
        return buildServerSslSocketFactoryFromPem();
    }

    @Nullable
    private SSLServerSocketFactory buildServerSslSocketFactoryFromPem() {
        InputStream keyIs = null;
        InputStream crtIs = null;
        try {
            // Private key (PKCS#8 unencrypted) and certificate (X.509)
            try { keyIs = getAssets().open("local_https_server.key"); } catch (Throwable ignored) {}
            try { crtIs = getAssets().open("local_https_server.crt"); } catch (Throwable ignored) {}

            if (keyIs == null || crtIs == null) {
                return null; // no PEM pair available
            }

            byte[] keyBytes = readAllBytes(keyIs);
            byte[] derKey = parsePemSection(keyBytes, "PRIVATE KEY");
            if (derKey == null) {
                Log.e("LocalTranslation", "PEM parse error: no PRIVATE KEY section");
                return null;
            }

            PrivateKey privateKey = null;
            Exception last = null;
            for (String alg : new String[]{"RSA", "EC", "DSA"}) {
                try {
                    KeyFactory kf = KeyFactory.getInstance(alg);
                    privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(derKey));
                    break;
                } catch (Exception e) {
                    last = e;
                }
            }
            if (privateKey == null) {
                Log.e("LocalTranslation", "Unable to construct PrivateKey: " + last);
                return null;
            }

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate cert = cf.generateCertificate(crtIs);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null);
            char[] pass = "changeit".toCharArray();
            ks.setKeyEntry("server", privateKey, pass, new Certificate[]{cert});

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
            Log.i("LocalTranslation", "HTTPS server configured from PEM assets.");
            return sslContext.getServerSocketFactory();
        } catch (Throwable t) {
            Log.e("LocalTranslation", "buildServerSslSocketFactoryFromPem error: " + t);
            return null;
        } finally {
            if (keyIs != null) try { keyIs.close(); } catch (Throwable ignored) {}
            if (crtIs != null) try { crtIs.close(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    @Nullable
    private static byte[] parsePemSection(byte[] pemBytes, String type) {
        String pem = new String(pemBytes, java.nio.charset.StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int s = pem.indexOf(begin);
        int e = pem.indexOf(end);
        if (s < 0 || e < 0 || e <= s) return null;
        String base64 = pem.substring(s + begin.length(), e).replaceAll("\\s", "");
        try {
            return Base64.decode(base64, Base64.DEFAULT);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalizeToMlkitCode(String lang) {
        if (lang == null) return null;
        try {
            String lower = lang.replace('_', '-').toLowerCase(Locale.ROOT);
            // Map all Chinese variants (zh, zh-CN, zh-TW, zh-HK, zh-Hans, zh-Hant...) to ML Kit "zh".
            if (lower.equals("zh") || lower.startsWith("zh-")) {
                return "zh";
            }
            String code = TranslateLanguage.fromLanguageTag(lang);
            return code;
        } catch (Throwable ignored) {
            return TranslateLanguage.fromLanguageTag(lang);
        }
    }

    private Translator createTranslator(String mlSrc, String mlDst) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(mlSrc)
                .setTargetLanguage(mlDst)
                .build();
        return Translation.getClient(options);
    }

    /**
     * Returns true if the language tag represents Traditional Chinese variants.
     */
    private static boolean isTraditionalChinese(String lang) {
        if (lang == null) return false;
        String lower = lang.replace('_', '-').toLowerCase(Locale.ROOT);
        return lower.equals("zh-tw");
    }

    /**
     * Converts Simplified Chinese text to Traditional Chinese using ICU transliteration.
     * Fallbacks to the original text if ICU is unavailable for any reason.
     */
    private static String toTraditionalChinese(String simplified) {
        if (simplified == null || simplified.isEmpty()) return simplified;
        try {
            android.icu.text.Transliterator tr = android.icu.text.Transliterator.getInstance("Hans-Hant");
            return tr.transliterate(simplified);
        } catch (Throwable t) {
            // fall through to return original
        }
        return simplified; // Fallback gracefully when ICU is unavailable or API < 29.
    }

    private static Map<String, String> parseQuery(String pathWithQuery) {
        Map<String, String> map = new HashMap<>();
        int qIdx = pathWithQuery.indexOf('?');
        if (qIdx < 0) return map;
        String qs = pathWithQuery.substring(qIdx + 1);
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = urlDecode(pair.substring(0, eq));
            String v = urlDecode(pair.substring(eq + 1));
            map.put(k, v);
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void respond(OutputStream os, int code, String body) throws IOException {
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        String status = switch (code) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n";
        String content = body == null ? "{}" : body;
        headers += "Content-Length: " + content.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n";
        bw.write(headers);
        bw.write(content);
        bw.flush();
    }

    private static String json(String k, String v) {
        return "{\"" + k + "\":" + jsonString(v) + "}";
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        String esc = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "\"" + esc + "\"";
    }
}
