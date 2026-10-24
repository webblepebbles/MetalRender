package com.pebbles_boon.metalrender.gui;

public enum UiTheme {
    LIQUID_GLASS(
            "Liquid Glass",
            0x2E0A0E16, 0x2E141C2A,
            0x1E000000,
            0x98303A46, 0x40FFFFFF, 0x48FFFFFF, 0x18FFFFFF,
            0x0016161B, 0x001E1E26,
            0x201E242E, 0xB0FFFFFF, 0x243E5268,
            0x30303C49, 0x484A5A6B, 0x25FFFFFF,
            0x20FFFFFF, 0xFFF2F6FF, 0xFFAEC0D4, 0xFF8ED9FF,
            0xFF34C759, 0xFFFF6B6B,
            0xFF5AC8FF, 0xFF8ED9FF, 0xFF2A3442, 0xFF4A5A6E,
            0xFF5AC8FF, 0xFF8ED9FF,
            0xFF6A7A8E, 0xFF5AC8FF, 0xFF5AC8FF, 0xFF3A444E,
            false, 20, 14),

    AMBER(
            "Amber",
            0xFF141412, 0xFF1B1A17,
            0x50000000,
            0xFF23221F, 0x24FFFFFF, 0x20FFFFFF, 0x08FFDAAA,
            0x0016161B, 0x001E1E26,
            0xFF1D1C19, 0xFF59472F, 0xFF343027,
            0xFF2C2A25, 0xFF343129, 0x14FFF0D0,
            0x10FFF0D0, 0xFFF6F1E8, 0xFFB9AF9F, 0xFFE9B86A,
            0xFF69C99D, 0xFFB5AFA8,
            0xFFD5A254, 0xFFE9B86A, 0xFF41403B, 0x30FFFFFF,
            0xFFD5A254, 0xFFE9B86A,
            0xFF726858, 0xFFD5A254, 0xFF69C99D, 0xFF605C54,
            false, 14, 12),

    PASTEL(
            "Pastel",
            0xFFE9E5DF, 0xFFE0DBD4,
            0x10443D35,
            0xFFF3EFE9, 0x18000000, 0x80FFFFFF, 0x18FFFFFF,
            0x00FFFFFF, 0x00FFFFFF,
            0xFFE5DFD7, 0xFFD4E0D7, 0xFFECE7E0,
            0xFFFAF7F2, 0xFFFFFFFF, 0x1462584E,
            0x1462584E, 0xFF393C37, 0xFF706F67, 0xFF527464,
            0xFF527C66, 0xFF847A73,
            0xFF718F80, 0xFF95AF9E, 0xFFD8D3CB, 0x1862584E,
            0xFF718F80, 0xFF95AF9E,
            0xFFB1AAA0, 0xFF718F80, 0xFF527C66, 0xFFA7A199,
            true, 14, 12),

    CRIMSON(
            "Crimson",
            0xFF171418, 0xFF201B20,
            0x50000000,
            0xFF272228, 0x24FFFFFF, 0x20FFFFFF, 0x08E5A1B5,
            0x0016161B, 0x001E1E26,
            0xFF211C22, 0xFF633848, 0xFF372B33,
            0xFF30282F, 0xFF3A3039, 0x18E5BBC9,
            0x12E5BBC9, 0xFFF8EDF1, 0xFFC3ABB6, 0xFFEA91AB,
            0xFF69C99D, 0xFFBEA6AF,
            0xFFCC6F8A, 0xFFEA91AB, 0xFF493C44, 0x30FFFFFF,
            0xFFCC6F8A, 0xFFEA91AB,
            0xFF806570, 0xFFCC6F8A, 0xFF69C99D, 0xFF66525C,
            false, 14, 12),

    LAVENDER(
            "Lavender",
            0xFF171620, 0xFF201E2B,
            0x50000000,
            0xFF272532, 0x24FFFFFF, 0x20FFFFFF, 0x08C4B4EA,
            0x0016161B, 0x001E1E26,
            0xFF201E2B, 0xFF4E426D, 0xFF342E45,
            0xFF302C40, 0xFF3A344C, 0x18D3C4F0,
            0x12D3C4F0, 0xFFF1EDFA, 0xFFB4ACC7, 0xFFC0A8EB,
            0xFF69C99D, 0xFFB6A9C2,
            0xFFA38BCF, 0xFFC0A8EB, 0xFF454052, 0x30FFFFFF,
            0xFFA38BCF, 0xFFC0A8EB,
            0xFF776C90, 0xFFA38BCF, 0xFF69C99D, 0xFF5F586E,
            false, 14, 12),

    CUSTOM(
            "Custom",
            0xFF14181E, 0xFF1E242E,
            0x70000000,
            0xDD1E242E, 0x40FFFFFF, 0x40FFFFFF, 0x14FFFFFF,
            0x0016161B, 0x001E1E26,
            0xFF1E242E, 0xFFFFFFFF, 0xFF2A3442,
            0xFF242E3A, 0xFF2E3846, 0x30FFFFFF,
            0x14FFFFFF, 0xFFFFFFFF, 0xFF9AA8B8, 0xFF7AC8FF,
            0xFF34C759, 0xFFFF6B6B,
            0xFF7AC8FF, 0xFF8ED9FF, 0xFF2A3442, 0xFF4A5A6E,
            0xFF7AC8FF, 0xFF8ED9FF,
            0xFF6A7A8E, 0xFF7AC8FF, 0xFF7AC8FF, 0xFF3A444E,
            false, 12, 12);

    public final String displayName;
    public final int bgTop, bgBottom, scrim;
    public final int panel, panelBorder, panelInnerStroke, panelHighlight;
    public final int header, headerGrad;
    public final int tabPillBg, tabPillActive, tabPillHover;
    public final int card, cardHover, cardStroke;
    public final int divider, textPri, textSec, textAccent;
    public final int valOn, valOff;
    public final int pillOn, pillOnGrad, pillOff, pillOffStroke;
    public final int accent, accentGrad;
    public final int scrollThumb, scrollThumbHover, glassDotOn, glassDotOff;
    public final boolean isLight;
    public final int cardRadius;
    public final int pillRadius;

    UiTheme(String displayName,
            int bgTop, int bgBottom, int scrim,
            int panel, int panelBorder, int panelInnerStroke, int panelHighlight,
            int header, int headerGrad,
            int tabPillBg, int tabPillActive, int tabPillHover,
            int card, int cardHover, int cardStroke,
            int divider, int textPri, int textSec, int textAccent,
            int valOn, int valOff,
            int pillOn, int pillOnGrad, int pillOff, int pillOffStroke,
            int accent, int accentGrad,
            int scrollThumb, int scrollThumbHover, int glassDotOn, int glassDotOff,
            boolean isLight, int cardRadius, int pillRadius) {
        this.displayName = displayName;
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.scrim = scrim;
        this.panel = panel;
        this.panelBorder = panelBorder;
        this.panelInnerStroke = panelInnerStroke;
        this.panelHighlight = panelHighlight;
        this.header = header;
        this.headerGrad = headerGrad;
        this.tabPillBg = tabPillBg;
        this.tabPillActive = tabPillActive;
        this.tabPillHover = tabPillHover;
        this.card = card;
        this.cardHover = cardHover;
        this.cardStroke = cardStroke;
        this.divider = divider;
        this.textPri = textPri;
        this.textSec = textSec;
        this.textAccent = textAccent;
        this.valOn = valOn;
        this.valOff = valOff;
        this.pillOn = pillOn;
        this.pillOnGrad = pillOnGrad;
        this.pillOff = pillOff;
        this.pillOffStroke = pillOffStroke;
        this.accent = accent;
        this.accentGrad = accentGrad;
        this.scrollThumb = scrollThumb;
        this.scrollThumbHover = scrollThumbHover;
        this.glassDotOn = glassDotOn;
        this.glassDotOff = glassDotOff;
        this.isLight = isLight;
        this.cardRadius = cardRadius;
        this.pillRadius = pillRadius;
    }

    public static UiTheme fromString(String s) {
        if (s == null)
            return LIQUID_GLASS;
        try {
            return valueOf(s.toUpperCase());
        } catch (Exception e) {
            if (s.equalsIgnoreCase("crimison") || s.equalsIgnoreCase("crimson"))
                return CRIMSON;
            if (s.equalsIgnoreCase("pastle") || s.equalsIgnoreCase("pastel"))
                return PASTEL;
            if (s.equalsIgnoreCase("amber"))
                return AMBER;
            if (s.equalsIgnoreCase("lavender"))
                return LAVENDER;
            if (s.equalsIgnoreCase("custom"))
                return CUSTOM;
            if (s.equalsIgnoreCase("liquid_glass") || s.equalsIgnoreCase("liquid glass"))
                return LIQUID_GLASS;
            return LIQUID_GLASS;
        }
    }

    public static int parseHex(String hex, int fallback) {
        if (hex == null)
            return fallback;
        String h = hex.trim().replace("#", "").replace("0x", "");
        if (h.length() == 6)
            h = "FF" + h;
        if (h.length() != 8)
            return fallback;
        try {
            return (int) Long.parseLong(h, 16);
        } catch (Exception e) {
            return fallback;
        }
    }
}
