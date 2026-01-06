package ciallo.jhll.powerstatusbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Typeface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

public class HookMain implements IXposedHookLoadPackage {
    private static TextView powerView = null;
    private static TextView tempView = null;
    private static TextView clockView = null;
    private static ScheduledExecutorService scheduler = null;
    private static Handler mainHandler = null;
    private static int lastColor = 0;

    private static boolean USE_mW = false; // true=使用mW/W, false=仅用W
    private static boolean TEXT_BOLD = true;
    private static final int UPDATE_INTERVAL_MS = 1500; // 每次刷新间隔（ms）
    private static final int TEXT_SP = 8; // 字体大小（sp）
    private static final int MARGIN_END_DP = 200; // 右侧 margin（dp）
    private static final int TOP_MARGIN_DP = 2; // 顶部 margin（dp）
    private static final int TEXT_GRAVITY = Gravity.END; // Gravity.CENTER / Gravity.END

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        logInfo("loaded for com.android.systemui");

        mainHandler = new Handler(Looper.getMainLooper());

        final String[] candidates = new String[]{
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                "com.android.systemui.statusbar.phone.StatusBarWindowView",
                "com.android.systemui.statusbar.phone.StatusBar",
                "com.android.systemui.statusbar.StatusBarView"
        };

        Class<?> targetClass = null;
        for (String name : candidates) {
            try {
                targetClass = XposedHelpers.findClassIfExists(name, lpparam.classLoader);
                if (targetClass != null) break;
            } catch (Throwable t) {}
        }

        if (targetClass == null) {
            logError("no statusbar class found");
            return;
        }

        XposedBridge.hookAllConstructors(targetClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    final Object thisObj = param.thisObject;
                    if (!(thisObj instanceof ViewGroup)) return;
                    final ViewGroup sb = (ViewGroup) thisObj;
                    final Context ctx = sb.getContext();

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (powerView != null && tempView != null) return; // 防重复

                                clockView = findClockView(sb);
                                LinearLayout container = new LinearLayout(ctx);
                                container.setOrientation(LinearLayout.VERTICAL);
                                container.setGravity(TEXT_GRAVITY);

                                tempView = new TextView(ctx);
                                tempView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
                                tempView.setGravity(TEXT_GRAVITY);
                                tempView.setPadding(dpToPx(ctx, 6), 0, dpToPx(ctx, 6), 0);
                                tempView.setText("--");
                                if (TEXT_BOLD) tempView.setTypeface(null, Typeface.BOLD);

                                powerView = new TextView(ctx);
                                powerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
                                powerView.setGravity(TEXT_GRAVITY);
                                powerView.setPadding(dpToPx(ctx, 6), 0, dpToPx(ctx, 6), 0);
                                powerView.setText("--");
                                if (TEXT_BOLD) powerView.setTypeface(null, Typeface.BOLD);

                                // 初始颜色跟随时钟
                                if (clockView != null) {
                                    // bug: 检测到了之后不是马上改而是修改颜色变量等下次刷新
                                    lastColor = clockView.getCurrentTextColor();
                                    powerView.setTextColor(lastColor);
                                    tempView.setTextColor(lastColor);
                                }

                                container.addView(tempView);
                                container.addView(powerView);

                                try {
                                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
                                    lp.setMargins(0, dpToPx(ctx, TOP_MARGIN_DP), dpToPx(ctx, MARGIN_END_DP), 0);
                                    sb.addView(container, lp);
                                } catch (Throwable t) {
                                    try { sb.addView(container, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)); } catch (Throwable e) {}
                                }

                                logInfo("UI injected successfully");
                                registerScreenReceiver(ctx);
                            } catch (Throwable e) {
                                logError("UI error: " + e.getMessage());
                            }
                        }
                    });
                } catch (Throwable t) {
                    logError("hook error: " + t.getMessage());
                }
            }
        });
    }

    private static void registerScreenReceiver(Context ctx) {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);

            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        logInfo("Screen ON detected, starting scheduler");
                        startScheduler();
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        logInfo("Screen OFF detected, stopping scheduler");
                        stopScheduler();
                    }
                }
            }, filter);
            // 启动时如果是亮屏，手动启动一次
            logInfo("Screen receiver registered, starting scheduler if screen is ON");
            startScheduler();
        } catch (Throwable t) {
            logError("registerScreenReceiver error: " + t.getMessage());
        }
    }

    private static void startScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    long pmw = readPower_mW();
                    int temp = readTemperature();

                    final String txtPower = formatMinimal(pmw);
                    final String txtTemp = (temp == Integer.MIN_VALUE) ? "--" : String.format("%.1f°C", temp / 10.0);

                    if (mainHandler != null && powerView != null && tempView != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    powerView.setText(txtPower);
                                    tempView.setText(txtTemp);
                                    if (clockView != null) {
                                        int color = clockView.getCurrentTextColor();
                                        if (color != lastColor) {
                                            powerView.setTextColor(color);
                                            tempView.setTextColor(color);
                                            lastColor = color;
                                        }
                                    }
                                } catch (Throwable t) {}
                            }
                        });
                    }
                } catch (Throwable t) {
                    logError("scheduler error: " + t.getMessage());
                }
            }
        }, 0, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logInfo("Scheduler started");
    }

    private static void stopScheduler() {
        if (scheduler != null) {
            try {
                scheduler.shutdownNow();
                logInfo("Scheduler stopped");
            } catch (Throwable ignored) {
                logError("Scheduler stop error: " + ignored.getMessage());
            }
            scheduler = null;
        }
    }

    private static TextView findClockView(ViewGroup root) {
        try {
            for (int i = 0; i < root.getChildCount(); i++) {
                android.view.View child = root.getChildAt(i);
                // 先判断是否为 TextView，并通过字符串判断是否可能为时钟（容错）
                if (child instanceof TextView) {
                    try {
                        String desc = child.toString();
                        if (desc != null && desc.toLowerCase().contains("clock")) {
                            return (TextView) child;
                        }
                    } catch (Throwable ignore) {}
                }
                // 如果是 ViewGroup，则递归搜索子视图
                if (child instanceof ViewGroup) {
                    TextView result = findClockView((ViewGroup) child);
                    if (result != null) return result;
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    private static long readLongFromFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return Long.MIN_VALUE;
            BufferedReader br = new BufferedReader(new FileReader(f));
            String s = br.readLine();
            br.close();
            if (s == null) return Long.MIN_VALUE;
            s = s.trim();
            if (s.length() == 0) return Long.MIN_VALUE;
            boolean neg = s.startsWith("-");
            if (neg) s = s.substring(1);
            long val = Long.parseLong(s);
            return neg ? -val : val;
        } catch (Throwable t) {
            return Long.MIN_VALUE;
        }
    }

    private static long readPower_mW() {
        try {
            String[] powerPaths = new String[]{
                    "/sys/class/power_supply/battery/power_now",
                    "/sys/class/power_supply/battery/power",
                    "/sys/class/power_supply/battery/batt_power",
                    "/sys/class/power_supply/ac/power_now"
            };
            for (String p : powerPaths) {
                long v = readLongFromFile(p);
                if (v != Long.MIN_VALUE) {
                    return v / 1000;
                }
            }

            long V = readLongFromFile("/sys/class/power_supply/battery/voltage_now");
            long I = readLongFromFile("/sys/class/power_supply/battery/current_now");
            if (V != Long.MIN_VALUE && I != Long.MIN_VALUE) {
                double pmw = (double) V * (double) I / 1e9;
                return Math.round(pmw);
            }

            V = readLongFromFile("/sys/class/power_supply/battery/voltage_avg");
            I = readLongFromFile("/sys/class/power_supply/battery/current_avg");
            if (V != Long.MIN_VALUE && I != Long.MIN_VALUE) {
                double pmw = (double) V * (double) I / 1e9;
                return Math.round(pmw);
            }
        } catch (Throwable t) {
            logError("readPower_mW error: " + t.getMessage());
        }
        return Long.MIN_VALUE;
    }

    private static int readTemperature() {
        try {
            String[] tempPaths = new String[]{
                    "/sys/class/power_supply/battery/temp",
                    "/sys/class/thermal/thermal_zone0/temp",
                    "/sys/class/thermal/thermal_zone1/temp"
            };
            for (String p : tempPaths) {
                long v = readLongFromFile(p);
                if (v != Long.MIN_VALUE) {
                    if (v > 1000) v = v / 1000;
                    return (int) v;
                }
            }
        } catch (Throwable t) {
            logError("readTemperature error: " + t.getMessage());
        }
        return Integer.MIN_VALUE;
    }

    private static String formatMinimal(long pmw) {
        if (pmw == Long.MIN_VALUE) return "--";
        boolean neg = pmw < 0;
        long ap = Math.abs(pmw);
        if (USE_mW) {
            if (ap < 1000) {
                return (neg?"+":"-") + ap + "mW";
            } else {
                double w = ap / 1000.0;
                return (neg?"+":"-") + String.format("%.2f", w) + "W";
            }
        } else {
            double w = ap / 1000.0;
            return (neg?"+":"-") + String.format("%.2f", w) + "W";
        }
    }

    private static int dpToPx(Context ctx, int dp) {
        float scale = ctx.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    private static void logInfo(String msg) {
        XposedBridge.log("[PowerStatusBar INFO] " + msg);
    }

    private static void logError(String msg) {
        XposedBridge.log("[PowerStatusBar ERROR] " + msg);
    }
}
