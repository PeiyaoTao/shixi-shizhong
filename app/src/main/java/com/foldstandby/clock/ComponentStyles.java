package com.foldstandby.clock;

import org.json.JSONObject;

/** Visual styles are independent of data options such as date range and forecast day. */
final class ComponentStyles {
    private static final String VECTOR_ARTWORK = "vectorArtwork";
    private static final String[][] IDS = {
        Scene.STYLES,
        {"month", "almanac", "week", "year", "date_dual"},
        {"agenda", "timeline", "agenda_cards", "spotlight", "agenda_dial"},
        {"weather", "weather_icon", "weather_panel", "weather_split", "weather_humidity"},
        {"cover", "record", "music_type", "spectrum", "music_tape"},
        {"focus_ring", "focus_type", "focus_segments", "hourglass", "focus_tiles"},
        {"gauge", "battery_cell", "battery_type", "battery_segments", "battery_ruler"},
        {"world_digital", "world_dial", "world_split", "world_orbit", "world_bands"},
        {"checklist", "todo_cards", "todo_numbers", "todo_progress", "todo_board"},
        {"alarm_digital", "alarm_dial", "alarm_countdown", "alarm_card", "alarm_ruler"}
    };
    private static final String[][] NAMES = {
        Scene.STYLE_NAMES,
        {"经典月历", "大字日签", "本周一览", "全年光点", "双月对照"},
        {"简洁清单", "时间轴", "日程卡片", "事项聚焦", "日程钟面"},
        {"极简温度", "天气图标", "气象面板", "温差刻度", "湿度水滴"},
        {"封面播放器", "黑胶唱片", "文字海报", "律动频谱", "卡带播放器"},
        {"专注圆环", "大字倒计时", "分段进度", "流沙计时", "翻片倒计时"},
        {"环形仪表", "电池轮廓", "大字电量", "能量分格", "电量标尺"},
        {"城市数字", "城市表盘", "双城对照", "昼夜轨道", "双城昼夜时带"},
        {"勾选清单", "待办卡片", "编号列表", "完成进度", "任务看板"},
        {"极简闹钟", "唤醒表盘", "剩余时间", "闹钟卡片", "响铃时刻尺"}
    };
    private static final String[][] NOTES = {
        {"", "", "", "", "", ""},
        {"月视图与可选今日事项", "票签排版突出日期与星期", "本周七日与今日事项", "十二个月的日期点阵", "本月和下月并排显示"},
        {"清晰分隔每条事项", "沿时间线排列日程", "双列排布的时间卡片", "突出所选范围的首条事项", "用 24 小时钟面定位事项"},
        {"大温度与天气摘要", "矢量天气符号与温度", "体感、湿度与高低温", "沿刻度查看当日温差", "水滴高度呈现相对湿度"},
        {"封面、曲名与播放控制", "唱片转动，唱臂随进度内移", "以曲名为主视觉", "按播放音频绘制低、中、高频", "曲名印在卡带标签上，卷盘随播放转动"},
        {"环形剩余进度", "远距离易读的倒计时", "分格显示剩余进度", "自然流沙、沙量变化与翻转复位", "分片显示剩余分钟和秒数"},
        {"电量、充电状态与温度", "横向电池与能量填充", "突出百分比与状态", "十格电量与充电指示", "纵向刻度显示精确电量"},
        {"城市、时间与 UTC 偏移", "独立时区的指针表盘", "所选时区与本地对照", "24 小时轨道与当前时刻", "对比两地的昼夜时段"},
        {"复选框与完成划线", "双列便签，显示两行任务文字", "编号排列与完成标记", "完成圆环与可勾选列表", "待完成和已完成各占一栏"},
        {"下次响铃时间与日期", "指针呈现下次响铃时刻", "按天、小时和分钟显示剩余时长", "票根排版显示响铃信息", "用 24 小时标尺定位响铃时刻"}
    };

    private ComponentStyles() {}
    private static int index(String type) {
        for (int i = 0; i < Scene.TYPES.length; i++) if (Scene.TYPES[i].equals(type)) return i;
        return 0;
    }
    static String[] ids(String type) { return IDS[index(type)]; }
    static String[] names(String type) { return NAMES[index(type)]; }
    static String[] notes(String type) { return NOTES[index(type)]; }
    static String selected(Scene.Component c) { return Scene.valid(c.style, ids(c.type), ids(c.type)[0]); }
    static int selectedIndex(Scene.Component c) {
        String[] ids = ids(c.type);
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(c.style)) return i;
        return 0;
    }
    static boolean hasMusicArtwork(Scene.Component c) {
        return c.type.equals("music") && (c.style.equals("record") || c.style.equals("music_tape"));
    }
    static boolean vectorArtwork(Scene.Component c) {
        JSONObject options = c.styles.optJSONObject(c.style);
        return options != null && options.optBoolean(VECTOR_ARTWORK, false);
    }
    static void setVectorArtwork(Scene.Component c, boolean vector) {
        Scene.put(c.styleOptions(), VECTOR_ARTWORK, vector);
    }
    static String restore(Scene.Component c, String savedStyle) {
        String remembered = c.options.optString("style:music");
        String restored = migrateMusicArtwork(c, remembered);
        if (!remembered.equals(restored)) Scene.put(c.options, "style:music", restored);
        if (c.type.equals("music")) savedStyle = migrateMusicArtwork(c, savedStyle);
        String[] styles = ids(c.type);
        return Scene.valid(savedStyle, styles, styles[0]);
    }
    private static String migrateMusicArtwork(Scene.Component c, String style) {
        String replacement;
        if (style.equals("record_vector")) replacement = "record";
        else if (style.equals("music_tape_vector")) replacement = "music_tape";
        else return style;
        JSONObject options = c.styles.optJSONObject(replacement);
        if (options == null) options = new JSONObject();
        Scene.put(options, VECTOR_ARTWORK, true);
        Scene.put(c.styles, replacement, options);
        return replacement;
    }
    static void select(Scene.Component c, String type, int variant) {
        c.setType(type);
        String[] ids = ids(c.type);
        c.style = ids[Math.max(0, Math.min(ids.length - 1, variant))];
        Scene.put(c.options, "style:" + c.type, c.style);
    }
}
