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

    // v1.4: 收益字段修改配置 (income.xxx=目标值)
    private static final java.util.Map<String, String> incomeCfg =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static final String CONF_PATH2 = "/data/data/com.giaoimgiao.qidianshow/files/qidianshow.conf";

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
        final ClassLoader cl = lpparam.classLoader;
        Class<?> wv = XposedHelpers.findClass("com.tencent.smtt.sdk.WebView", cl);
        Class<?> req = XposedHelpers.findClass("com.tencent.smtt.export.external.interfaces.WebResourceRequest", cl);

        // hook 重载1: shouldInterceptRequest(WebView, WebResourceRequest)
        // beforeHookedMethod: 记录URL + 对收益接口接管(带完整请求头同步抓取, setResult 替换响应)
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
                            }
                            // 收益接口: 接管响应(带 X5 完整请求头同步抓取, 成功后替换返回)
                            if (isIncomeUrl(url)) {
                                try {
                                    Object headersObj = XposedHelpers.callMethod(
                                            param.args[1], "getRequestHeaders");
                                    @SuppressWarnings("unchecked")
                                    java.util.Map<String, String> headers =
                                            (java.util.Map<String, String>) headersObj;
                                    byte[] raw = fetchIncomeBody(cl, url, headers);
                                    if (raw != null && raw.length > 0) {
                                        logResponse("H5-RAW", url, raw);
                                        byte[] body = applyIncomeConfig(url, raw);
                                        if (body != raw) {
                                            logResponse("H5-MOD", url, body);
                                        }
                                        Class<?> wrr = XposedHelpers.findClass(
                                                "com.tencent.smtt.export.external.interfaces.WebResourceResponse", cl);
                                        Object resp = XposedHelpers.newInstance(wrr,
                                                "application/json", "utf-8",
                                                new java.io.ByteArrayInputStream(body));
                                        param.setResult(resp);
                                    }
                                } catch (Throwable t) {
                                    log("收益拦截失败: " + url + " err=" + t);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // 收益 body 缓存(5分钟), 避免同一 URL 重复抓取
    private static final java.util.Map<String, CacheEntry> bodyCache = new java.util.concurrent.ConcurrentHashMap<String, CacheEntry>();
    private static final Object CACHE_LOCK = new Object();

    private static class CacheEntry {
        byte[] data;
        long ts;
        CacheEntry(byte[] d, long t) { data = d; ts = t; }
    }

    /**
     * v1.3: 带 X5 完整请求头 + Cookie 同步抓取收益接口 body.
     * 之前独立抓包 4001 是因为 CookieManager 用错 classloader 导致 cookie 未带上.
     */
    private byte[] fetchIncomeBody(final ClassLoader cl, final String url,
                                   final java.util.Map<String, String> headers) {
        try {
            long now = System.currentTimeMillis();
            CacheEntry ce = bodyCache.get(url);
            if (ce != null && now - ce.ts < 300000) return ce.data;
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            if (headers != null) {
                for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                    try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) { }
                }
            }
            // X5 CookieManager (用 App classloader, 之前用错 loader 导致 cookie 丢失)
            try {
                Object cm = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.tencent.smtt.sdk.CookieManager", cl), "getInstance");
                String cookie = (String) XposedHelpers.callMethod(cm, "getCookie", url);
                if (cookie != null && !cookie.isEmpty())
                    conn.setRequestProperty("Cookie", cookie);
            } catch (Throwable ignored) { }
            int code = conn.getResponseCode();
            if (code >= 400) {
                log("fetch HTTP " + code + ": " + url);
                conn.disconnect();
                return null;
            }
            InputStream in = conn.getInputStream();
            byte[] data = readAll(in, MAX_BODY);
            in.close();
            conn.disconnect();
            if (data.length > 0) {
                synchronized (CACHE_LOCK) {
                    bodyCache.put(url, new CacheEntry(data, System.currentTimeMillis()));
                }
            }
            return data;
        } catch (Throwable t) {
            log("fetch失败: " + url + " err=" + t);
            return null;
        }
    }

    /**
     * 收益相关接口(最终要改数字的目标)
     */
    private boolean isIncomeUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("/income/") || u.contains("incomedata")
                || u.contains("incomebynovellines") || u.contains("incomemaxmonth")
                || u.contains("getallcompany") || u.contains("incomewelfare")
                || u.contains("incomecopyright") || u.contains("incometax");
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
        incomeCfg.clear();
        // 优先私有目录(设置界面写入), 其次外部配置文件(手动编辑)
        String[] paths = {CONF_PATH2, CONF_PATH};
        for (String p : paths) {
            try {
                File f = new File(p);
                if (!f.exists()) continue;
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
                    if ("enabled".equals(k)) {
                        cfgEnabled = !"0".equals(v);
                    } else if (k.startsWith("income.") && !v.isEmpty()) {
                        incomeCfg.put(k.substring("income.".length()), v);
                    }
                }
                br.close();
                if (p.equals(CONF_PATH2)) break; // 私有目录存在则优先且不再读外部
            } catch (Throwable ignored) {
            }
        }
        log("配置: enabled=" + cfgEnabled + " income字段=" + incomeCfg.size());
    }

    /**
     * v1.4/1.5: 按配置修改收益接口响应.
     * - incomedataV2: 修改 result.income 字段
     * - getwelfarbymonth: 修改福利汇总(total/rewards) + 明细 records[].welfarincome/rewards
     * 只替换配置了的目标值, 未配置字段保持原样; 结构不匹配时原样返回.
     */
    private byte[] applyIncomeConfig(String url, byte[] body) {
        if (body == null || incomeCfg.isEmpty()) return body;
        try {
            String s = new String(body, "UTF-8");
            if (!s.contains("\"income\"") && !s.contains("\"welfarDetail\"") && !s.contains("\"total\"")) return body;
            org.json.JSONObject root = new org.json.JSONObject(s);
            org.json.JSONObject result = root.optJSONObject("result");
            if (result == null) return body;
            boolean changed = false;

            if (url != null && url.contains("getwelfarbymonth")) {
                // 福利汇总: total <- incomeTotal, rewards <- welfarecount
                String total = incomeCfg.get("incomeTotal");
                String wc = incomeCfg.get("welfarecount");
                if (total != null && result.has("total")) {
                    result.put("total", total);
                    changed = true;
                }
                if (wc != null && result.has("rewards")) {
                    result.put("rewards", wc);
                    changed = true;
                }
                // 福利明细 records[].welfarincome/rewards
                org.json.JSONObject wd = result.optJSONObject("welfarDetail");
                if (wd != null && wc != null) {
                    org.json.JSONArray records = wd.optJSONArray("records");
                    if (records != null) {
                        for (int i = 0; i < records.length(); i++) {
                            org.json.JSONObject rec = records.optJSONObject(i);
                            if (rec != null) {
                                if (rec.has("welfarincome")) { rec.put("welfarincome", wc); changed = true; }
                                if (rec.has("rewards")) { rec.put("rewards", wc); changed = true; }
                            }
                        }
                    }
                }
            } else {
                // incomedataV2: result.income 字段直接替换
                org.json.JSONObject income = result.optJSONObject("income");
                if (income == null) return body;
                for (java.util.Map.Entry<String, String> e : incomeCfg.entrySet()) {
                    if (income.has(e.getKey())) {
                        income.put(e.getKey(), e.getValue());
                        changed = true;
                    }
                }
            }
            if (!changed) return body;
            log("收益改写(" + (url != null && url.contains("getwelfarbymonth") ? "福利" : "汇总") + "): " + incomeCfg);
            return root.toString().getBytes("UTF-8");
        } catch (Throwable t) {
            log("收益改写失败: " + t);
            return body;
        }
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