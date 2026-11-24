package com.foldstandby.clock;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deep inspector rendering parameters, data sources, permissions, and palette scopes for every component type. */
final class ComponentInspector {
    interface Host {
        void onDraftChanged();
        void onRebuild();
        void onSwitchComponentType(String newType);
        void onSelectPaletteScope(int scope);
        void onEmbedPalette(LinearLayout host, int scope);
        void onOpenTimezone(Scene.Component c);
        void onOpenWeatherSettings();
        void onPickFocusDuration();
        void onOpenFocusSettings();
        void onOpenPermissionSettings();
        void onCalendarAccess();
        void onMusicAccess();
        void onImportMusicLyrics();
        void onChooseMusicLyrics();
        void onAdjustMusicLyrics();
        void onSpectrumAccess(boolean enabled);
        void onOpenMotionSettings();
        void onOpenTodoInput(String title, String initial, Consumer<String> onValue);
        TodoStore todoStore();
        void onOpenTodoManager(String list, boolean add);
    }

    private static final String[] WORLD_NAMES = { "上海", "东京", "伦敦", "纽约", "悉尼", "巴黎" };
    private static final String[] WORLD_ZONES = {
        "Asia/Shanghai", "Asia/Tokyo", "Europe/London", "America/New_York", "Australia/Sydney", "Europe/Paris"
    };

    private ComponentInspector() {}

    static void render(
        Context c,
        SharedPreferences prefs,
        LinearLayout container,
        Scene.Component comp,
        int group,
        int paletteScope,
        int availWidthDp,
        Host host
    ) {
        boolean isClock = comp.type.equals("clock");
        if (isClock && group == 0) {
            renderClockStyleGroup(c, container, comp, availWidthDp, host);
        } else if (isClock && group == 1) {
            renderClockDetailGroup(c, container, comp, host);
        } else if ((isClock && group == 2) || (!isClock && group == 1)) {
            renderPaletteGroup(c, container, paletteScope, availWidthDp, host);
        } else {
            renderContentGroup(c, prefs, container, comp, availWidthDp, host);
        }
    }

    private static void renderClockStyleGroup(
        Context c,
        LinearLayout container,
        Scene.Component comp,
        int availWidthDp,
        Host host
    ) {
        LinearLayout fontCard = UiKit.card(c, container, "字形与大小", null);
        if (!comp.style.equals("led") && !comp.style.equals("dot")) {
            UiKit.choice(
                c,
                fontCard,
                "字体",
                new String[] { "紧凑数字", "简洁无衬线", "等宽", "衬线" },
                comp.options.optInt("font"),
                n -> {
                    Scene.put(comp.options, "font", n);
                    host.onDraftChanged();
                    host.onRebuild();
                }
            );
        }
        UiKit.slider(
            c,
            fontCard,
            "显示大小",
            comp.options.optInt("scale", 100),
            75,
            125,
            "%",
            v -> {
                Scene.put(comp.options, "scale", v);
                host.onDraftChanged();
            }
        );


    }

    private static void renderClockDetailGroup(
        Context c,
        LinearLayout container,
        Scene.Component comp,
        Host host
    ) {
        JSONObject o = comp.styleOptions();
        if (comp.style.equals("analog") || comp.style.equals("industrial")) {
            boolean industrial = comp.style.equals("industrial");
            LinearLayout dialCard = UiKit.card(c, container, "表盘刻度与数字", null);
            UiKit.toggle(c, dialCard, "小时刻度", o.optBoolean("major", true), v -> { Scene.put(o, "major", v); host.onDraftChanged(); });
            UiKit.toggle(c, dialCard, "分钟刻度", o.optBoolean("minor", true), v -> { Scene.put(o, "minor", v); host.onDraftChanged(); });
            UiKit.choice(
                c,
                dialCard,
                "数字标签",
                new String[] { "无数字", "12 / 3 / 6 / 9", "全部数字", "罗马数字" },
                o.optInt("labels", industrial ? 1 : 0),
                n -> {
                    Scene.put(o, "labels", n);
                    host.onDraftChanged();
                    host.onRebuild();
                }
            );
            UiKit.toggle(c, dialCard, "平滑秒针", o.optBoolean("smooth", false), v -> { Scene.put(o, "smooth", v); host.onDraftChanged(); });

            LinearLayout sizeCard = UiKit.card(c, container, "刻度与指针尺寸", null);
            UiKit.slider(c, sizeCard, "小时刻度长度", o.optInt("majorLength", industrial ? 19 : 15), 6, 28, "", v -> { Scene.put(o, "majorLength", v); host.onDraftChanged(); });
            UiKit.slider(c, sizeCard, "分钟刻度长度", o.optInt("minorLength", 6), 2, 16, "", v -> { Scene.put(o, "minorLength", v); host.onDraftChanged(); });
            UiKit.slider(c, sizeCard, "小时刻度粗细", o.optInt("majorWidth", industrial ? 5 : 3), 1, 7, "", v -> { Scene.put(o, "majorWidth", v); host.onDraftChanged(); });
            UiKit.slider(c, sizeCard, "分钟刻度粗细", o.optInt("minorWidth", 1), 1, 4, "", v -> { Scene.put(o, "minorWidth", v); host.onDraftChanged(); });
            UiKit.slider(c, sizeCard, "指针粗细", o.optInt("handWidth", industrial ? 9 : 6), 3, 12, "", v -> { Scene.put(o, "handWidth", v); host.onDraftChanged(); });
        } else if (comp.style.equals("flip")) {
            LinearLayout flipCard = UiKit.card(c, container, "机械翻页参数", null);
            UiKit.slider(c, flipCard, "翻页时长", o.optInt("duration", 420), 240, 800, " ms", v -> { Scene.put(o, "duration", v); host.onDraftChanged(); });
            UiKit.toggle(c, flipCard, "秒数也使用翻页", o.optBoolean("flipSeconds", false), v -> { Scene.put(o, "flipSeconds", v); host.onDraftChanged(); });
            UiKit.toggle(c, flipCard, "翻页声音", o.optBoolean("sound", false), v -> { Scene.put(o, "sound", v); host.onDraftChanged(); });
            UiKit.toggle(c, flipCard, "翻页触感", o.optBoolean("haptic", false), v -> { Scene.put(o, "haptic", v); host.onDraftChanged(); });
            UiKit.note(c, flipCard, "全屏时每分钟最多反馈一次；声音跟随媒体音量，触感跟随系统设置。夜间关闭。");
        } else if (comp.style.equals("led")) {
            LinearLayout ledCard = UiKit.card(c, container, "七段数码参数", null);
            UiKit.slider(c, ledCard, "数码笔画粗细", o.optInt("thickness", 5), 2, 8, "", v -> { Scene.put(o, "thickness", v); host.onDraftChanged(); });
        } else if (comp.style.equals("dot")) {
            LinearLayout dotCard = UiKit.card(c, container, "点阵参数", null);
            UiKit.slider(c, dotCard, "点阵大小", o.optInt("radius", 4), 2, 7, "", v -> { Scene.put(o, "radius", v); host.onDraftChanged(); });
            UiKit.toggle(c, dotCard, "方形点阵", o.optBoolean("square", false), v -> { Scene.put(o, "square", v); host.onDraftChanged(); });
        }
    }

    private static void renderPaletteGroup(
        Context c,
        LinearLayout container,
        int paletteScope,
        int availWidthDp,
        Host host
    ) {
        LinearLayout card = UiKit.card(c, container, "配色作用域", null);
        UiKit.chips(
            c,
            card,
            availWidthDp,
            new String[] { "场景", "此页", "此组件", "夜间" },
            paletteScope,
            host::onSelectPaletteScope
        );
        UiKit.gap(c, card, 4);
        host.onEmbedPalette(card, paletteScope);
    }

    private static void renderContentGroup(
        Context c,
        SharedPreferences prefs,
        LinearLayout container,
        Scene.Component comp,
        int availWidthDp,
        Host host
    ) {
        LinearLayout baseCard = UiKit.card(c, container, "内容", null);
        if (comp.type.equals("clock")) {
            UiKit.toggle(c, baseCard, "显示日期", comp.bool("date", true), v -> { Scene.put(comp.options, "date", v); host.onDraftChanged(); });
            UiKit.toggle(c, baseCard, "显示秒数", comp.bool("seconds", false), v -> { Scene.put(comp.options, "seconds", v); host.onDraftChanged(); });
            UiKit.toggle(c, baseCard, "24 小时制", comp.bool("h24", true), v -> { Scene.put(comp.options, "h24", v); host.onDraftChanged(); });
        }

        switch (comp.type) {
            case "alarm":
                renderAlarmCard(c, container, comp, host);
                break;
            case "todo":
                renderTodoCard(c, container, comp, host);
                break;
            case "date":
                renderDateOrCalendarCard(c, container, comp, true, host);
                break;
            case "calendar":
                renderDateOrCalendarCard(c, container, comp, false, host);
                break;
            case "weather":
                renderWeatherCard(c, prefs, container, comp, host);
                break;
            case "world":
                renderWorldCard(c, prefs, container, comp, availWidthDp, host);
                break;
            case "focus":
                renderFocusCard(c, prefs, container, host);
                break;
            case "music":
                renderMusicCard(c, prefs, container, host);
                break;
            case "battery":
                renderBatteryCard(c, container, host);
                break;
            default:
                break;
        }
    }

    private static void typeSwitcher(Context c, LinearLayout hostCard, Scene.Component comp, Host host) {
        UiKit.choice(
            c,
            hostCard,
            "组件内容",
            Scene.TYPE_NAMES,
            Arrays.asList(Scene.TYPES).indexOf(comp.type),
            n -> host.onSwitchComponentType(Scene.TYPES[n])
        );
    }

    private static void renderTodoCard(
        Context c,
        LinearLayout container,
        Scene.Component comp,
        Host host
    ) {
        TodoStore store = host.todoStore();
        if (store != null) {
            renderTodoListSettings(c, container, comp, host, store);
            return;
        }
        LinearLayout card = UiKit.card(c, container, "本地待办事项", null);
        JSONArray tasks = TodoItems.all(comp);
        for (int i = 0; i < tasks.length(); i++) {
            final int n = i;
            JSONObject task = tasks.optJSONObject(i);
            if (task == null) continue;
            LinearLayout row = UiKit.row(c);
            CheckBox done = new CheckBox(c);
            done.setChecked(task.optBoolean("done"));
            done.setContentDescription("完成待办 · " + task.optString("text"));
            done.setMinHeight(UiKit.dp(c, 36));
            done.setOnCheckedChangeListener((v, checked) -> {
                Scene.put(task, "done", checked);
                host.onDraftChanged();
            });
            row.addView(done);
            TextView item = UiKit.button(c, task.optString("text"), () ->
                AppDialogs.builder(c)
                    .setTitle(task.optString("text"))
                    .setItems(new String[] { "修改内容", "删除" }, (d, which) -> {
                        if (which == 1) {
                            TodoItems.remove(comp, n);
                            host.onDraftChanged();
                            host.onRebuild();
                            if(c instanceof MainActivity)((MainActivity)c).deletedForEditor("已删除待办");
                        } else {
                            host.onOpenTodoInput("待办内容", task.optString("text"), value -> {
                                TodoItems.edit(comp, n, value);
                                host.onDraftChanged();
                                host.onRebuild();
                            });
                        }
                    })
                    .show()
            );
            row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.bottomMargin = UiKit.dp(c, 6);
            card.addView(row, rlp);
        }
        UiKit.action(card.getContext(), card, "＋ 添加待办", () ->
            host.onOpenTodoInput("待办内容", "", value -> {
                TodoItems.add(comp, value);
                host.onDraftChanged();
                host.onRebuild();
            })
        );
        UiKit.action(c, card, "锁屏隐私设置  ›", host::onOpenPermissionSettings);
    }

    private static void renderTodoListSettings(Context context, LinearLayout container, Scene.Component component,
                                               Host host, TodoStore store) {
        LinearLayout card = UiKit.card(context, container, "待办", null);
        String list = store.listFor(component);
        UiKit.action(context, card, "显示清单 · " + store.listName(list) + "  ›", () -> {
            Map<String, String> lists = store.lists();
            String[] ids = lists.keySet().toArray(new String[0]);
            String[] names = lists.values().toArray(new String[0]);
            AppDialogs.builder(context).setTitle("选择显示清单")
                    .setSingleChoiceItems(names, Arrays.asList(ids).indexOf(list), (dialog, choice) -> {
                        dialog.dismiss();
                        Scene.put(component.options, "todoList", ids[choice]);
                        host.onDraftChanged();
                        host.onRebuild();
                    }).setNegativeButton("取消", null).show();
        });
        UiKit.action(context, card, "＋ 添加待办", () -> host.onOpenTodoManager(list, true));
        UiKit.action(context, card, "管理待办  ›", () -> host.onOpenTodoManager(list, false));
        UiKit.action(context, card, "锁屏隐私设置  ›", host::onOpenPermissionSettings);
    }

    private static void renderAlarmCard(Context c, LinearLayout container, Scene.Component comp, Host host) {
        LinearLayout card = alarmDataCard(c, container);
        UiKit.toggle(c, card, "24 小时制", comp.bool("h24", true), v -> { Scene.put(comp.options, "h24", v); host.onDraftChanged(); });
        UiKit.toggle(c, card, "显示响铃日期", comp.bool("alarmDate", true), v -> { Scene.put(comp.options, "alarmDate", v); host.onDraftChanged(); });
        UiKit.toggle(c, card, "显示剩余时间", comp.bool("alarmCountdown", true), v -> { Scene.put(comp.options, "alarmCountdown", v); host.onDraftChanged(); });

    }

    static LinearLayout alarmDataCard(Context c, LinearLayout container) {
        LinearLayout card = UiKit.card(c, container, "系统闹钟", null);
        TextView status = UiKit.text(c, AlarmData.status(System.currentTimeMillis()), 11, UiKit.MUTED);
        status.setTag("alarm:status");
        card.addView(status);
        UiKit.action(c, card, "＋ 添加闹钟", () -> AlarmData.open(c, true));
        UiKit.action(c, card, "管理系统闹钟", () -> AlarmData.open(c, false));
        UiKit.note(c, card, "闹钟由系统时钟管理。");
        return card;
    }

    private static void renderDateOrCalendarCard(
        Context c,
        LinearLayout container,
        Scene.Component comp,
        boolean isMonthDate,
        Host host
    ) {
        LinearLayout card = UiKit.card(c, container, isMonthDate ? "月历与日程联动" : "日程范围", null);
        if (isMonthDate) {
            UiKit.toggle(c, card, "同时显示今日事项", comp.bool("showAgenda", true), v -> {
                Scene.put(comp.options, "showAgenda", v);
                host.onDraftChanged();
                host.onRebuild();
                if (v && c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    host.onCalendarAccess();
                }
            });
        } else {
            UiKit.toggle(c, card, "只显示今天", comp.bool("agendaToday", false), v -> { Scene.put(comp.options, "agendaToday", v); host.onDraftChanged(); });
        }
        if (!isMonthDate || comp.bool("showAgenda", true)) {
            boolean granted = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
            UiKit.action(c, card, "日历访问 · " + (granted ? "已允许" : "未授权") + "  ›", host::onCalendarAccess);
        }
    }

    private static void renderWeatherCard(
        Context c,
        SharedPreferences prefs,
        LinearLayout container,
        Scene.Component comp,
        Host host
    ) {
        LinearLayout card = UiKit.card(c, container, "天气预报", null);
        UiKit.toggle(c, card, "显示明日预报", comp.bool("tomorrow", false), v -> { Scene.put(comp.options, "tomorrow", v); host.onDraftChanged(); });
        String city = WeatherCities.simplified(prefs.getString("city", "未设置"));
        UiKit.action(c, card, "天气地点 · " + city + "  ›", host::onOpenWeatherSettings);
    }

    private static void renderWorldCard(
        Context c,
        SharedPreferences prefs,
        LinearLayout container,
        Scene.Component comp,
        int availWidthDp,
        Host host
    ) {
        String currentZone = comp.options.optString("zone", prefs.getString("zone", "Asia/Shanghai"));
        LinearLayout card = UiKit.card(c, container, "世界时钟与时区", null);
        UiKit.action(c, card, "时区 · " + currentZone, () -> host.onOpenTimezone(comp));
        int quickSelected = -1;
        for (int i = 0; i < WORLD_ZONES.length; i++) {
            if (WORLD_ZONES[i].equals(currentZone)) {
                quickSelected = i;
                break;
            }
        }
        UiKit.chips(c, card, availWidthDp, WORLD_NAMES, quickSelected, n -> {
            Scene.put(comp.options, "zone", WORLD_ZONES[n]);
            host.onDraftChanged();
            host.onRebuild();
        });
        UiKit.toggle(c, card, "24 小时制", comp.bool("h24", true), v -> { Scene.put(comp.options, "h24", v); host.onDraftChanged(); });
    }

    private static void renderFocusCard(
        Context c,
        SharedPreferences prefs,
        LinearLayout container,
        Host host
    ) {
        LinearLayout card = UiKit.card(c, container, "番茄专注计时", "所有专注组件共用计时与默认时长");
        UiKit.action(c, card, "默认专注时长 · " + FocusDuration.label(DeskData.focusMinutes(prefs)) + "  ›", host::onPickFocusDuration);
        UiKit.action(c, card, "专注计时设置  ›", host::onOpenFocusSettings);
    }

    private static void renderMusicCard(
        Context c,
        SharedPreferences prefs,
        LinearLayout container,
        Host host
    ) {
        LinearLayout card = UiKit.card(c, container, "媒体控制与会话权限", null);
        boolean allowed = DeskData.hasMusicAccess(c);
        UiKit.action(c, card, "音乐访问 · " + (allowed ? "已开启" : "未开启") + "  ›", host::onMusicAccess);
        UiKit.toggle(c, card, "第三方实时频谱（可选）", MusicSpectrum.enabled(c, prefs), host::onSpectrumAccess);
        UiKit.note(c, card, "开启时 Android 会请求录音权限，用于分析设备正在播放的声音。");
        LinearLayout lyrics = UiKit.card(c, container, "歌词", null);
        UiKit.toggle(c, lyrics, "联网查找歌词", OnlineLyrics.enabled(prefs),
                enabled -> prefs.edit().putBoolean(MusicLyrics.ONLINE, enabled).apply());
        UiKit.note(c, lyrics, "优先读取播放器原生歌词（元数据、状态栏歌词、蓝牙歌词与歌词广播）；未提供时自动向 LRCLIB 匹配。");
        UiKit.action(c, lyrics, "换歌词版本  ›", host::onChooseMusicLyrics);
        UiKit.action(c, lyrics, "调整歌词时间  ›", host::onAdjustMusicLyrics);
        UiKit.action(c, lyrics, "导入当前曲目的 LRC 歌词  ›", host::onImportMusicLyrics);
    }

    private static void renderBatteryCard(
        Context c,
        LinearLayout container,
        Host host
    ) {
        LinearLayout card = UiKit.card(c, container, "电量仪表与动态效果", null);
        String summary = String.format(
            Locale.ROOT,
            "当前状态：%d%% · %s · 电池温度 %.1f°C",
            DeskData.batteryLevel,
            DeskData.batteryFull ? "已充满" : DeskData.batteryCharging ? "正在充电" : DeskData.batteryPlugged ? "已连接电源" : "电池供电",
            DeskData.batteryTempTenths / 10f
        );
        UiKit.note(c, card, summary);
        UiKit.action(c, card, "全局动效设置  ›", host::onOpenMotionSettings);
    }
}
