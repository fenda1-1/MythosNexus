package com.mythos.nexus.ui;

/**
 * Nexus UI palette. Fields are non-final so a theme preset or the theme
 * panel can retint the whole app at runtime — every consumer reads these
 * as live values.
 */
public final class NexusTheme {
    private NexusTheme() {}

    public static int BG_APP    = 0xFFF7E9D6; // screen backdrop — warm cream
    public static int BG_SIDEBAR= 0xFFF2DFC4; // brand rail — deeper cream
    public static int BG_CARD   = 0xFFFEF6EE; // card / raised surface
    public static int BG_CARD_HOV = 0xFFFFF9F1; // card hover — brighter cream
    public static int BG_INPUT  = 0xFFFFFFFF; // pure white input

    public static int BORDER_SOFT   = 0xFFE6C191; // default card border
    public static int BORDER_STRONG = 0xFFD9A76B; // selected / button border
    public static int BORDER_ACCENT = 0xFFC76F3F; // focused / hover accent border

    public static int TEXT_TITLE  = 0xFF5B3A28; // header / primary text
    public static int TEXT_LABEL  = 0xFF6E4B36; // labels / values
    public static int TEXT_SUB    = 0xFF9B7656; // secondary / placeholder
    public static int TEXT_FAINT  = 0xFFB08C6E; // very soft, hints only

    public static int ACCENT      = 0xFFE07A44; // selected state, primary CTA
    public static int ACCENT_DEEP = 0xFFB45A3C; // deeper accent (title right / cursor)

    public static int BTN_BG        = 0xFFF2E1CB;
    public static int BTN_BG_HOV    = 0xFFE9D2B0;
    public static int BTN_BG_PRESS  = 0xFFDCC196;
    public static int BTN_BORDER    = 0xFFD9A76B;
    public static int BTN_BORDER_HOV= 0xFFC76F3F;

    public static int OK   = 0xFF8FBF6A; // sage green
    public static int WARN = 0xFFE4B047; // amber
    public static int ERR  = 0xFFD97757; // soft terracotta red
    public static int IDLE = 0xFFD8C9B8; // beige gray

    public static int SHADOW = 0x33B06A30;  // warm brown shadow
    public static int DIVIDER= 0xFFEAD8BC;  // hairline between sections
    public static int HOVER_WASH = 0x1AFFFFFF; // subtle white wash on hover

    public static int RADIUS_CARD  = 8;
    public static int RADIUS_BTN   = 6;
    public static int RADIUS_INPUT = 5;
    public static int SHADOW_OFFSET= 3;

    //  Theme presets + live editing

    /** The 18 tunable color fields, in draw order — theme panel iterates. */
    public static final String[] COLOR_KEYS = {
        "BG_APP","BG_SIDEBAR","BG_CARD","BG_CARD_HOV","BG_INPUT",
        "BORDER_SOFT","BORDER_STRONG","BORDER_ACCENT",
        "TEXT_TITLE","TEXT_LABEL","TEXT_SUB","TEXT_FAINT",
        "ACCENT","ACCENT_DEEP",
        "BTN_BG","BTN_BG_HOV","BTN_BG_PRESS","BTN_BORDER","BTN_BORDER_HOV",
        "OK","WARN","ERR","IDLE",
        "DIVIDER","HOVER_WASH","SHADOW",
    };

    /** Chinese display names for COLOR_KEYS — theme panel shows these. */
    public static final java.util.Map<String,String> COLOR_LABELS = buildLabels();
    private static java.util.Map<String,String> buildLabels() {
        java.util.Map<String,String> m = new java.util.HashMap<>();
        m.put("BG_APP", "全局背景");
        m.put("BG_SIDEBAR", "侧栏背景");
        m.put("BG_CARD", "卡片背景");
        m.put("BG_CARD_HOV", "卡片悬停");
        m.put("BG_INPUT", "输入框背景");
        m.put("BORDER_SOFT", "边框（淡）");
        m.put("BORDER_STRONG", "边框（深）");
        m.put("BORDER_ACCENT", "强调边框");
        m.put("TEXT_TITLE", "标题文字");
        m.put("TEXT_LABEL", "正文文字");
        m.put("TEXT_SUB", "次要文字");
        m.put("TEXT_FAINT", "弱化文字");
        m.put("ACCENT", "主题色");
        m.put("ACCENT_DEEP", "深色主题");
        m.put("BTN_BG", "按钮背景");
        m.put("BTN_BG_HOV", "按钮悬停");
        m.put("BTN_BG_PRESS", "按钮按下");
        m.put("BTN_BORDER", "按钮边框");
        m.put("BTN_BORDER_HOV", "按钮边框悬停");
        m.put("OK", "成功（绿）");
        m.put("WARN", "警告（黄）");
        m.put("ERR", "错误（红）");
        m.put("IDLE", "闲置（灰）");
        m.put("DIVIDER", "分割线");
        m.put("HOVER_WASH", "悬停高亮");
        m.put("SHADOW", "阴影");
        return m;
    }
    public static String labelOf(String key) {
        String l = COLOR_LABELS.get(key);
        return l != null ? l : key;
    }

    /** Read a color field by name. */
    public static int get(String key) {
        switch (key) {
            case "BG_APP": return BG_APP;
            case "BG_SIDEBAR": return BG_SIDEBAR;
            case "BG_CARD": return BG_CARD;
            case "BG_CARD_HOV": return BG_CARD_HOV;
            case "BG_INPUT": return BG_INPUT;
            case "BORDER_SOFT": return BORDER_SOFT;
            case "BORDER_STRONG": return BORDER_STRONG;
            case "BORDER_ACCENT": return BORDER_ACCENT;
            case "TEXT_TITLE": return TEXT_TITLE;
            case "TEXT_LABEL": return TEXT_LABEL;
            case "TEXT_SUB": return TEXT_SUB;
            case "TEXT_FAINT": return TEXT_FAINT;
            case "ACCENT": return ACCENT;
            case "ACCENT_DEEP": return ACCENT_DEEP;
            case "BTN_BG": return BTN_BG;
            case "BTN_BG_HOV": return BTN_BG_HOV;
            case "BTN_BG_PRESS": return BTN_BG_PRESS;
            case "BTN_BORDER": return BTN_BORDER;
            case "BTN_BORDER_HOV": return BTN_BORDER_HOV;
            case "OK": return OK;
            case "WARN": return WARN;
            case "ERR": return ERR;
            case "IDLE": return IDLE;
            case "DIVIDER": return DIVIDER;
            case "HOVER_WASH": return HOVER_WASH;
            case "SHADOW": return SHADOW;
            default: return 0xFF000000;
        }
    }

    /** Write a color field by name. */
    public static void set(String key, int argb) {
        switch (key) {
            case "BG_APP": BG_APP = argb; break;
            case "BG_SIDEBAR": BG_SIDEBAR = argb; break;
            case "BG_CARD": BG_CARD = argb; break;
            case "BG_CARD_HOV": BG_CARD_HOV = argb; break;
            case "BG_INPUT": BG_INPUT = argb; break;
            case "BORDER_SOFT": BORDER_SOFT = argb; break;
            case "BORDER_STRONG": BORDER_STRONG = argb; break;
            case "BORDER_ACCENT": BORDER_ACCENT = argb; break;
            case "TEXT_TITLE": TEXT_TITLE = argb; break;
            case "TEXT_LABEL": TEXT_LABEL = argb; break;
            case "TEXT_SUB": TEXT_SUB = argb; break;
            case "TEXT_FAINT": TEXT_FAINT = argb; break;
            case "ACCENT": ACCENT = argb; break;
            case "ACCENT_DEEP": ACCENT_DEEP = argb; break;
            case "BTN_BG": BTN_BG = argb; break;
            case "BTN_BG_HOV": BTN_BG_HOV = argb; break;
            case "BTN_BG_PRESS": BTN_BG_PRESS = argb; break;
            case "BTN_BORDER": BTN_BORDER = argb; break;
            case "BTN_BORDER_HOV": BTN_BORDER_HOV = argb; break;
            case "OK": OK = argb; break;
            case "WARN": WARN = argb; break;
            case "ERR": ERR = argb; break;
            case "IDLE": IDLE = argb; break;
            case "DIVIDER": DIVIDER = argb; break;
            case "HOVER_WASH": HOVER_WASH = argb; break;
            case "SHADOW": SHADOW = argb; break;
        }
    }

    /** A named preset = display name + color table. */
    public static final class Preset {
        public final String id;
        public final String name;
        public final java.util.Map<String,Integer> colors = new java.util.HashMap<>();
        public Preset(String id, String name) { this.id = id; this.name = name; }
        Preset c(String k, int argb) { colors.put(k, argb); return this; }
    }

    /** Built-in presets — shipped with the mod. Custom themes append at runtime. */
    public static final Preset[] PRESETS = buildPresets();

    /** Custom themes the user created / imported — kept in a mutable list so
     *  the panel can scroll them and the config can persist them. */
    public static final java.util.List<Preset> CUSTOM = new java.util.ArrayList<>();

    /** All selectable themes = PRESETS ∪ CUSTOM. */
    public static java.util.List<Preset> all() {
        java.util.List<Preset> out = new java.util.ArrayList<>(PRESETS.length + CUSTOM.size());
        java.util.Collections.addAll(out, PRESETS);
        out.addAll(CUSTOM);
        return out;
    }

    private static Preset[] buildPresets() {
        Preset memphis = new Preset("memphis", "孟菲斯")
            .c("BG_APP",0xFFFEF9EF).c("BG_SIDEBAR",0xFFFFE3B3)
            .c("BG_CARD",0xFFFFFCF5).c("BG_CARD_HOV",0xFFFFFFFF)
            .c("BG_INPUT",0xFFFFFFFF)
            .c("BORDER_SOFT",0xFF000000).c("BORDER_STRONG",0xFF000000)
            .c("BORDER_ACCENT",0xFFFF6B6B)
            .c("TEXT_TITLE",0xFF000000).c("TEXT_LABEL",0xFF141414)
            .c("TEXT_SUB",0xFF4A4A4A).c("TEXT_FAINT",0xFF909090)
            .c("ACCENT",0xFFFF6B6B).c("ACCENT_DEEP",0xFFE53E3E)
            .c("BTN_BG",0xFFFECA57).c("BTN_BG_HOV",0xFFFF9FF3)
            .c("BTN_BG_PRESS",0xFF48DBFB)
            .c("BTN_BORDER",0xFF000000).c("BTN_BORDER_HOV",0xFF000000)
            .c("OK",0xFF1DD1A1).c("WARN",0xFFFECA57)
            .c("ERR",0xFFFF6B6B).c("IDLE",0xFFD2D2D2)
            .c("DIVIDER",0xFF000000).c("HOVER_WASH",0x26FFFFFF)
            .c("SHADOW",0xFF000000);

        Preset bauhaus = new Preset("bauhaus", "包豪斯")
            .c("BG_APP",0xFFFFFFFF).c("BG_SIDEBAR",0xFFF4F4F4)
            .c("BG_CARD",0xFFFFFFFF).c("BG_CARD_HOV",0xFFFAFAFA)
            .c("BG_INPUT",0xFFFFFFFF)
            .c("BORDER_SOFT",0xFF000000).c("BORDER_STRONG",0xFF000000)
            .c("BORDER_ACCENT",0xFFDD0000)
            .c("TEXT_TITLE",0xFF000000).c("TEXT_LABEL",0xFF0A0A0A)
            .c("TEXT_SUB",0xFF666666).c("TEXT_FAINT",0xFF9A9A9A)
            .c("ACCENT",0xFFDD0000).c("ACCENT_DEEP",0xFF9E0000)
            .c("BTN_BG",0xFFDD0000).c("BTN_BG_HOV",0xFFFFD700)
            .c("BTN_BG_PRESS",0xFF0057B8)
            .c("BTN_BORDER",0xFF000000).c("BTN_BORDER_HOV",0xFF000000)
            .c("OK",0xFF0057B8).c("WARN",0xFFFFD700)
            .c("ERR",0xFFDD0000).c("IDLE",0xFFBFBFBF)
            .c("DIVIDER",0xFF000000).c("HOVER_WASH",0x14FFFFFF)
            .c("SHADOW",0x00000000);

        Preset minimal = new Preset("minimal", "极简扁平")
            .c("BG_APP",0xFFFFFFFF).c("BG_SIDEBAR",0xFFFAFAFA)
            .c("BG_CARD",0xFFFFFFFF).c("BG_CARD_HOV",0xFFF2F2F2)
            .c("BG_INPUT",0xFFFFFFFF)
            .c("BORDER_SOFT",0xFF000000).c("BORDER_STRONG",0xFF000000)
            .c("BORDER_ACCENT",0xFFFF3366)
            .c("TEXT_TITLE",0xFF000000).c("TEXT_LABEL",0xFF141414)
            .c("TEXT_SUB",0xFF777777).c("TEXT_FAINT",0xFFAAAAAA)
            .c("ACCENT",0xFFFF3366).c("ACCENT_DEEP",0xFFD91E5A)
            .c("BTN_BG",0xFF000000).c("BTN_BG_HOV",0xFFFF3366)
            .c("BTN_BG_PRESS",0xFF222222)
            .c("BTN_BORDER",0xFF000000).c("BTN_BORDER_HOV",0xFF000000)
            .c("OK",0xFF00D4AA).c("WARN",0xFFFFCC00)
            .c("ERR",0xFFFF3366).c("IDLE",0xFF999999)
            .c("DIVIDER",0xFFE5E5E5).c("HOVER_WASH",0x10FFFFFF)
            .c("SHADOW",0x00000000);

        Preset macos = new Preset("macos", "macOS 毛玻璃")
            .c("BG_APP",0xFF1C1C1E).c("BG_SIDEBAR",0xFF1C1C1E)
            .c("BG_CARD",0xFF2C2C2E).c("BG_CARD_HOV",0xFF3A3A3C)
            .c("BG_INPUT",0xFF1C1C1E)
            .c("BORDER_SOFT",0x14FFFFFF).c("BORDER_STRONG",0x20FFFFFF)
            .c("BORDER_ACCENT",0xFF0A84FF)
            .c("TEXT_TITLE",0xF2FFFFFF).c("TEXT_LABEL",0xB3FFFFFF)
            .c("TEXT_SUB",0x66FFFFFF).c("TEXT_FAINT",0x4DFFFFFF)
            .c("ACCENT",0xFF0A84FF).c("ACCENT_DEEP",0xFF0066CC)
            .c("BTN_BG",0xFF3A3A3C).c("BTN_BG_HOV",0xFF4A4A4C)
            .c("BTN_BG_PRESS",0xFF2C2C2E)
            .c("BTN_BORDER",0x20FFFFFF).c("BTN_BORDER_HOV",0x40FFFFFF)
            .c("OK",0xFF30D158).c("WARN",0xFFFFD60A)
            .c("ERR",0xFFFF453A).c("IDLE",0xFF636366)
            .c("DIVIDER",0x14FFFFFF).c("HOVER_WASH",0x14FFFFFF)
            .c("SHADOW",0x4D000000);

        Preset pixel = new Preset("pixel", "像素艺术")
            .c("BG_APP",0xFF1A1C2C).c("BG_SIDEBAR",0xFF1A1C2C)
            .c("BG_CARD",0xFF22273A).c("BG_CARD_HOV",0xFF2E3550)
            .c("BG_INPUT",0xFFF4F4F4)
            .c("BORDER_SOFT",0xFF1A1C2C).c("BORDER_STRONG",0xFF1A1C2C)
            .c("BORDER_ACCENT",0xFF29ADFF)
            .c("TEXT_TITLE",0xFFF4F4F4).c("TEXT_LABEL",0xFFF4F4F4)
            .c("TEXT_SUB",0xFF8B8680).c("TEXT_FAINT",0xFF5F574F)
            .c("ACCENT",0xFFFF004D).c("ACCENT_DEEP",0xFF7E2553)
            .c("BTN_BG",0xFFFF004D).c("BTN_BG_HOV",0xFF29ADFF)
            .c("BTN_BG_PRESS",0xFF00E436)
            .c("BTN_BORDER",0xFF1A1C2C).c("BTN_BORDER_HOV",0xFFFFEC27)
            .c("OK",0xFF00E436).c("WARN",0xFFFFEC27)
            .c("ERR",0xFFFF004D).c("IDLE",0xFF565768)
            .c("DIVIDER",0xFF1A1C2C).c("HOVER_WASH",0x1AFFFFFF)
            .c("SHADOW",0xFF000000);

        Preset cream = new Preset("cream", "暖阳奶油")
            .c("BG_APP",0xFFF7E9D6).c("BG_SIDEBAR",0xFFF2DFC4)
            .c("BG_CARD",0xFFFEF6EE).c("BG_CARD_HOV",0xFFFFF9F1)
            .c("BG_INPUT",0xFFFFFFFF)
            .c("BORDER_SOFT",0xFFE6C191).c("BORDER_STRONG",0xFFD9A76B)
            .c("BORDER_ACCENT",0xFFC76F3F)
            .c("TEXT_TITLE",0xFF5B3A28).c("TEXT_LABEL",0xFF6E4B36)
            .c("TEXT_SUB",0xFF9B7656).c("TEXT_FAINT",0xFFB08C6E)
            .c("ACCENT",0xFFE07A44).c("ACCENT_DEEP",0xFFB45A3C)
            .c("BTN_BG",0xFFF2E1CB).c("BTN_BG_HOV",0xFFE9D2B0)
            .c("BTN_BG_PRESS",0xFFDCC196)
            .c("BTN_BORDER",0xFFD9A76B).c("BTN_BORDER_HOV",0xFFC76F3F)
            .c("OK",0xFF8FBF6A).c("WARN",0xFFE4B047)
            .c("ERR",0xFFD97757).c("IDLE",0xFFD8C9B8)
            .c("DIVIDER",0xFFEAD8BC).c("HOVER_WASH",0x1AFFFFFF)
            .c("SHADOW",0x33B06A30);

        return new Preset[]{ cream, memphis, bauhaus, minimal, macos, pixel };
    }

    /** Apply a preset — live-retint the whole UI. */
    public static void apply(Preset p) {
        for (String k : COLOR_KEYS) {
            Integer v = p.colors.get(k);
            if (v != null) set(k, v);
        }
    }

    /** Snapshot current colors as a Preset — for "save current as theme". */
    public static Preset snapshot(String id, String name) {
        Preset p = new Preset(id, name);
        for (String k : COLOR_KEYS) p.c(k, get(k));
        return p;
    }

    /** Find preset by id — searches built-ins and custom. */
    public static Preset preset(String id) {
        for (Preset p : PRESETS) if (p.id.equals(id)) return p;
        for (Preset p : CUSTOM) if (p.id.equals(id)) return p;
        return null;
    }


    /**
     * Strict JSON format for AI-generated themes. The prompt the "AI 生成"
     * button copies instructs the model to emit exactly this shape.
     */
    public static final String AI_FORMAT_PROMPT =
        "You are generating a UI theme for a Minecraft mod panel. "
      + "Output ONLY a single JSON object — no markdown, no explanation, no prose.\n"
      + "Required JSON shape (all fields mandatory):\n"
      + "{\n"
      + "  \"name\": \"主题中文名（4 字内）\",\n"
      + "  \"colors\": {\n"
      + "    \"BG_APP\": \"#RRGGBB\", \"BG_SIDEBAR\": \"#RRGGBB\",\n"
      + "    \"BG_CARD\": \"#RRGGBB\", \"BG_CARD_HOV\": \"#RRGGBB\",\n"
      + "    \"BG_INPUT\": \"#RRGGBB\",\n"
      + "    \"BORDER_SOFT\": \"#RRGGBB\", \"BORDER_STRONG\": \"#RRGGBB\",\n"
      + "    \"BORDER_ACCENT\": \"#RRGGBB\",\n"
      + "    \"TEXT_TITLE\": \"#RRGGBB\", \"TEXT_LABEL\": \"#RRGGBB\",\n"
      + "    \"TEXT_SUB\": \"#RRGGBB\", \"TEXT_FAINT\": \"#RRGGBB\",\n"
      + "    \"ACCENT\": \"#RRGGBB\", \"ACCENT_DEEP\": \"#RRGGBB\",\n"
      + "    \"BTN_BG\": \"#RRGGBB\", \"BTN_BG_HOV\": \"#RRGGBB\",\n"
      + "    \"BTN_BG_PRESS\": \"#RRGGBB\",\n"
      + "    \"BTN_BORDER\": \"#RRGGBB\", \"BTN_BORDER_HOV\": \"#RRGGBB\",\n"
      + "    \"OK\": \"#RRGGBB\", \"WARN\": \"#RRGGBB\", \"ERR\": \"#RRGGBB\",\n"
      + "    \"IDLE\": \"#RRGGBB\",\n"
      + "    \"DIVIDER\": \"#RRGGBB\", \"HOVER_WASH\": \"#RRGGBB\",\n"
      + "    \"SHADOW\": \"#RRGGBB\"\n"
      + "  }\n"
      + "}\n"
      + "Rules:\n"
      + "1. Every color is \"#RRGGBB\" — 6 hex digits, no alpha.\n"
      + "2. Use ALL 26 keys exactly as written — no omissions, no extras.\n"
      + "3. Ensure AA contrast between TEXT_* and BG_* colors.\n"
      + "4. Output JSON only — no code fence, no commentary.\n"
      + "Now generate a theme matching this description: ";

    /**
     * Parse a theme from the strict AI format. Returns the Preset or null on
     * any validation failure (missing key, bad hex, extra garbage).
     */
    public static Preset parseThemeJson(String json) {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonParser()
                    .parse(json).getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : "自定义";
            com.google.gson.JsonObject cols = o.getAsJsonObject("colors");
            if (cols == null) return null;
            Preset p = new Preset("custom_" + System.currentTimeMillis(), name);
            for (String k : COLOR_KEYS) {
                if (!cols.has(k)) return null;        // every key required
                String hex = cols.get(k).getAsString();
                if (!hex.matches("#[0-9A-Fa-f]{6}")) return null;
                int argb = 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
                p.c(k, argb);
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
