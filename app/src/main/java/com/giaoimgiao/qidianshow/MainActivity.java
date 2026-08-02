package com.giaoimgiao.qidianshow;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * QidianShow 配置界面 v1
 * 起点作家助手收益数据展示修改
 * 配置: /sdcard/Download/qidianshow.conf
 */
public class MainActivity extends Activity {

    private static final String CONFIG_FILE = "qidianshow.conf";
    private static final String REPO_URL = "https://github.com/giaoimgiao/qidian-show";

    private Switch swEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("📖 起点Show v1 (监听版)");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("起点作家助手 com.yuewen.authorapp\n收益数据接口监听抓包 · 暂未改写");
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(14));
        root.addView(sub);

        LinearLayout rowSw = new LinearLayout(this);
        rowSw.setOrientation(LinearLayout.HORIZONTAL);
        rowSw.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvSw = new TextView(this);
        tvSw.setText("模块总开关");
        tvSw.setTextSize(16);
        tvSw.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        swEnabled = new Switch(this);
        rowSw.addView(tvSw);
        rowSw.addView(swEnabled);
        root.addView(rowSw);

        Button btnSave = new Button(this);
        btnSave.setText("💾 保存配置");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
            }
        });
        root.addView(btnSave);

        Button btnRepo = new Button(this);
        btnRepo.setText("⭐ GitHub 仓库");
        btnRepo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开浏览器: " + e, Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(btnRepo);

        TextView tip = new TextView(this);
        tip.setText("\n使用说明:\n"
                + "1. 在 LSPosed 中启用本模块并勾选 作家助手\n"
                + "2. 重启 作家助手\n"
                + "3. 打开「收入」页, 操作各 tab (总收入/月收入趋势/税务明细等)\n"
                + "4. 抓包日志: /data/data/com.yuewen.authorapp/files/qidianshow.log\n"
                + "   或 /sdcard/Download/qidianshow.log\n"
                + "5. 配置保存在 " + Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS) + "/" + CONFIG_FILE);
        tip.setTextSize(12);
        tip.setPadding(0, dp(14), 0, 0);
        root.addView(tip);

        loadConfig();
        setContentView(scroll);
    }

    private void loadConfig() {
        try {
            File conf = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), CONFIG_FILE);
            if (!conf.exists()) return;
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(conf), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("enabled".equals(k)) swEnabled.setChecked(!"0".equals(v));
            }
            br.close();
        } catch (Exception ignored) {
        }
    }

    private void saveConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("# QidianShow config by giaoimgiao\n");
        sb.append("# 仓库: https://github.com/giaoimgiao/qidian-show\n");
        sb.append("enabled=").append(swEnabled.isChecked() ? "1" : "0").append("\n");
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File conf = new File(dir, CONFIG_FILE);
            FileOutputStream fos = new FileOutputStream(conf);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(sb.toString());
            w.flush();
            w.close();
            Toast.makeText(this, "✅ 配置已保存到 " + conf.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "❌ 保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}