package com.giaoimgiao.qidianshow;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * QidianShow v1.4 —— 模块设置界面
 * 配置: /sdcard/Download/qidianshow.conf (income.字段=目标值, 留空=不修改)
 */
public class MainActivity extends Activity {

    private static final String CONF_PATH = "/sdcard/Download/qidianshow.conf";

    // 收益字段: [配置key, 中文名]
    private static final String[][] FIELDS = {
            {"income.showincome", "展示收入 showincome"},
            {"income.welfarecount", "福利收入 welfarecount"},
            {"income.othercount", "其他收入 othercount"},
            {"income.channeljituan", "集团渠道 channeljituan"},
            {"income.channelyido", "三方渠道 channelyido"},
            {"income.channelother", "其他渠道 channelother"},
            {"income.copyrightPay", "版权收入 copyrightPay"},
            {"income.freeincome", "免费收入 freeincome"},
            {"income.incomeTotal", "★ 总收入 incomeTotal"},
            {"income.r_selfTotal", "自营收入 r_selfTotal"},
            {"income.r_otherTotal", "其他收入 r_otherTotal"},
    };

    private EditText[] eds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadConfig();
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("起点Show 收益修改配置");
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("修改后保存, 重启作家助手生效。留空表示不修改该字段。\n配置写入 " + CONF_PATH);
        tip.setTextSize(12);
        tip.setPadding(0, 12, 0, 24);
        root.addView(tip);

        eds = new EditText[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            TextView label = new TextView(this);
            label.setText(FIELDS[i][1]);
            label.setTextSize(14);
            label.setPadding(0, 10, 0, 4);
            root.addView(label);

            EditText et = new EditText(this);
            et.setSingleLine(true);
            et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            et.setHint("留空=不修改");
            root.addView(et);
            eds[i] = et;
        }

        Button save = new Button(this);
        save.setText("保存配置");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (saveConfig()) {
                    Toast.makeText(MainActivity.this, "已保存, 请重启作家助手生效", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "保存失败, 请检查存储权限", Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(save);
        return sv;
    }

    private void loadConfig() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                for (int i = 0; i < FIELDS.length; i++) {
                    if (FIELDS[i][0].equals(k)) {
                        eds[i].setText(v);
                        break;
                    }
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
    }

    private boolean saveConfig() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# QidianShow config by giaoimgiao\n");
            sb.append("# 仓库: https://github.com/giaoimgiao/qidian-show\n");
            sb.append("# 收益字段(留空=不修改该字段)\n");
            sb.append("enabled=1\n");
            for (int i = 0; i < FIELDS.length; i++) {
                String v = eds[i].getText().toString().trim();
                sb.append(FIELDS[i][0]).append('=').append(v).append('\n');
            }
            File f = new File(CONF_PATH);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
