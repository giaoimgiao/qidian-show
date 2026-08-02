package com.giaoimgiao.qidianshow;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * QidianShow v1 —— 监听版
 * 目标: 起点作家助手 com.yuewen.authorapp 3.82.0.1541
 *
 * hook 层:
 *  1. OkHttp RealCall.getResponseWithInterceptorChain —— 记录原生接口(ccauthorweb等)
 *  2. X5 WebViewClient.shouldInterceptRequest ×3 —— 记录 H5 页面收益接口响应体
 *
 * 日志: /data/data/com.yuewen.authorapp/files/qidianshow.log
 * 配置: /sdcard/Download/qidianshow.conf (enabled=1/0)
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TARGET = "com.yuewen.authorapp";
    private static final String LOG_PATH = "/data/data/com.yuewen.authorapp/files/qidianshow.log";
    private static final String LOG_PATH2 = "/sdcard/Download/qidianshow.log";
    private static final String CONF_PATH = "/sdcard/Download/qidianshow.conf";

    private static volatile boolean cfgEnabled = true;
    private static final Object LOG_LOCK = new Object();
    private static final int MAX_BODY = 60000; // 单响应体记录上限

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET)) return;

        log("========== QidianShow v1 注入成功 ==========");
        log("target=" + lpparam.packageName + " ver=" + lpparam.processName);
        loadConfig();

        try {
            hookOkHttp(lpparam);
            log("OkHttp hook 完成");
        } catch (Throwable t) {
            log("OkHttp hook 失败: " + t);
        }
        try {
            hookX5WebViewClient(lpparam);
            log("X5 WebViewClient hook 完成");
        } catch (Throwable t) {
            log("X5 WebViewClient hook 失败: " + t);
        }
        log("全部 hook 注册完毕");
    }

    // ==================== OkHttp 原生接口监听 ====================

    private void hookOkHttp(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // OkHttp3.x 通用: RealCall.getResponseWithInterceptorChain() 返回最终 Response
        XposedHelpers.findAndHookMethod("okhttp3.RealCall", lpparam.classLoader,
                "getResponseWithInterceptorChain", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            Object resp = param.getResult();
                            if (resp == null) return;
                            String url = String.valueOf(XposedHelpers.callMethod(
                                    XposedHelpers.callMethod(resp, "request"), "url"));
                            if (!isInterestingNative(url)) return;

                            // peekBody 不消费原 body, 安全记录
                            Object peek = XposedHelpers.callMethod(resp, "peekBody", 1024L * 1024L);
                            byte[] body = (byte[]) XposedHelpers.callMethod(
                                    XposedHelpers.callMethod(peek, "source"), "readByteArray");
                            logResponse("NATIVE", url, body);
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    private boolean isInterestingNative(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        // 原生业务接口
        if (u.contains("ccauthorweb")) return true;
        if (u.contains("income") || u.contains("settle") || u.contains("finance")
                || u.contains("author")) return true;
        if (u.contains("statistic") || u.contains("stat")) return true;
        return false;
    }

    // ==================== X5 WebView H5 监听 ====================

    // 最近记录的URL去重(同一请求X5会链式调用多次)
    private static String lastUrl = "";
    private static long lastUrlTs = 0;

    private void hookX5WebViewClient(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        ClassLoader cl = lpparam.classLoader;
        Class<?> wv = XposedHelpers.findClass("com.tencent.smtt.sdk.WebView", cl);
        Class<?> req = XposedHelpers.findClass("com.tencent.smtt.export.external.interfaces.WebResourceRequest", cl);

        // 只 hook 重载1: shouldInterceptRequest(WebView, WebResourceRequest)
        // beforeHookedMethod 只记录URL, 完全不触碰返回值, 零副作用
        XposedHelpers.findAndHookMethod("com.tencent.smtt.sdk.WebViewClient", cl,
                "shouldInterceptRequest", wv, req, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            String url = String.valueOf(XposedHelpers.callMethod(
                                    param.args[1], "getUrl"));
                            if (url == null || url.isEmpty()) return;
                            // 3秒内同一URL只记一次
                            long now = System.currentTimeMillis();
                            if (url.equals(lastUrl) && now - lastUrlTs < 3000) return;
                            lastUrl = url;
                            lastUrlTs = now;
                            if (isInterestingH5(url)) {
                                log("WEB-REQ: " + url);
                                // v1.1: 对核心收益接口尝试独立抓取 body(不影响页面)
                                tryFetchBody(url);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    /**
     * v1.1: 主动独立请求抓取收益接口响应体.
     * 注意: X5 的 shouldInterceptRequest 返回 null 时不经过回调, 拿不到 body.
     * 这里用独立 HttpURLConnection 请求相同 URL 获取 body 记录(不改变页面数据流).
     */
    private void tryFetchBody(final String url) {
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                                new java.net.URL(url).openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("User-Agent", "yuewenAuthorApp/3.82.0.1541");
                        // 带上 X5 cookie(登录态)
                        try {
                            Object cm = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("com.tencent.smtt.sdk.CookieManager", conn.getClass().getClassLoader()),
                                    "getInstance");
                            String cookie = (String) XposedHelpers.callMethod(cm, "getCookie", url);
                            if (cookie != null && !cookie.isEmpty())
                                conn.setRequestProperty("Cookie", cookie);
                        } catch (Throwable ignored) {
                        }
                        int code = conn.getResponseCode();
                        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                        byte[] data = readAll(in, MAX_BODY);
                        in.close();
                        logResponse("H5-BODY", url + " [HTTP " + code + "]", data);
                        conn.disconnect();
                    } catch (Throwable t2) {
                        log("H5-BODY 抓取失败: " + url + " err=" + t2);
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    private boolean isInterestingH5(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        // 静态资源排除
        if (u.contains(".js") || u.contains(".css") || u.contains(".png")
                || u.contains(".jpg") || u.contains(".jpeg") || u.contains(".gif")
                || u.contains(".webp") || u.contains(".ico") || u.contains(".woff")
                || u.contains(".ttf") || u.contains(".svg")) return false;
        // 收益/统计/数据类接口
        if (u.contains("income") || u.contains("statistic") || u.contains("/stat")
                || u.contains("finance") || u.contains("settle") || u.contains("money")
                || u.contains("report") || u.contains("data")) return true;
        // 兜底: 记录 JSON 类接口(html页面也记录)
        return u.contains("json") || u.endsWith(".html") || u.contains("ccauthorweb");
    }

    // ==================== 日志/配置 ====================

    private void loadConfig() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return;
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("enabled".equals(k)) cfgEnabled = !"0".equals(v);
            }
            br.close();
        } catch (Throwable ignored) {
        }
        log("配置: enabled=" + cfgEnabled);
    }

    private void logResponse(String tag, String url, byte[] body) {
        if (body == null) return;
        String bodyStr;
        try {
            bodyStr = new String(body, "UTF-8");
        } catch (Throwable t) {
            bodyStr = "<binary>";
        }
        if (bodyStr.length() > MAX_BODY) bodyStr = bodyStr.substring(0, MAX_BODY) + "...(截断)";
        log("[" + tag + "] " + url + " BODY(" + body.length + "): " + bodyStr);
    }

    private byte[] readAll(InputStream in, int max) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > max) break;
        }
        return bos.toByteArray();
    }

    private static void log(String msg) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        synchronized (LOG_LOCK) {
            try {
                File f = new File(LOG_PATH);
                if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
            try {
                File f2 = new File(LOG_PATH2);
                FileOutputStream fos2 = new FileOutputStream(f2, true);
                fos2.write(line.getBytes("UTF-8"));
                fos2.close();
            } catch (Throwable ignored) {
            }
        }
    }
}