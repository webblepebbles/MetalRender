package com.pebbles_boon.metalrender.gui;

public enum UiTheme {
  LIQUID_GLASS(
      "Liquid Glass",
      0x2E0A0E16, 0x2E141C2A,
      0x1E000000,
      0x331E242E, 0x66FFFFFF, 0x66FFFFFF, 0x26FFFFFF,
      0x0016161B, 0x001E1E26,
      0x281E242E, 0xB0FFFFFF, 0x331E242E,
      0x2E28303A, 0x4038404A, 0x40FFFFFF,
      0x20FFFFFF, 0xFFF2F6FF, 0xFFAEC0D4, 0xFF8ED9FF,
      0xFF34C759, 0xFFFF6B6B,
      0xFF5AC8FF, 0xFF8ED9FF, 0xFF2A3442, 0xFF4A5A6E,
      0xFF5AC8FF, 0xFF8ED9FF,
      0xFF6A7A8E, 0xFF5AC8FF, 0xFF5AC8FF, 0xFF3A444E,
      false, 20, 14),

  AMBER(
      "Amber",
      0xFF120B06, 0xFF1A130A,
      0x801A0F02,
      0xCC2A1D0F, 0x33FFA500, 0x22FFB84D, 0x14FFB84D,
      0x0016161B, 0x001E1E26,
      0xFF2A1D0F, 0xFFFFF0D6, 0xFF3A2E1E,
      0xFF2D2215, 0xFF3A2E1E, 0x33FFA500,
      0x33FFA500, 0xFFFFF8EC, 0xFFC9B99A, 0xFFFFB84D,
      0xFF34C759, 0xFFFF453A,
      0xFFFF8C2E, 0xFFFFB84D, 0xFF3A2E1E, 0xFF5A4630,
      0xFFFF8C2E, 0xFFFFB84D,
      0xFF6A5A3A, 0xFFFF8C2E, 0xFFFFB84D, 0xFF48484F,
      false, 10, 12),

  PASTEL(
      "Pastel",
      0xFFE6D9CE, 0xFFD8C6B8,
      0x184D3A36,
      0xFFF0E6DA, 0xFFD8C6B8, 0x30FFFFFF, 0x06C9A090,
      0x00FFFFFF, 0x00FFFFFF,
      0xFFEADFD3, 0xFFF8F0E6, 0xFFF0E2D4,
      0xFFF6EEE4, 0xFFFBEFE2, 0xFFD8C4B2,
      0x14C9A090, 0xFF4D3A36, 0xFF7A6A64, 0xFF9A7E70,
      0xFF6FAE90, 0xFFC98A8A,
      0xFFC9A090, 0xFFD8B8A6, 0xFFE6D5C6, 0xFFC0B0A4,
      0xFFC9A090, 0xFFD8B8A6,
      0xFFB8A898, 0xFFC09888, 0xFF7AB89A, 0xFFB0A8A0,
      true, 16, 14),

  CRIMSON(
      "Crimson",
      0xFF13080A, 0xFF1A0E12,
      0x80180018,
      0xCC241418, 0x33FF3B5C, 0x22FF3B5C, 0x14FF3B5C,
      0x0016161B, 0x001E1E26,
      0xFF2A1A1E, 0xFFFFE8EC, 0xFF3A242A,
      0xFF2E1C20, 0xFF3A242A, 0x44FF3B5C,
      0x22FF3B5C, 0xFFFFF0F2, 0xFFC9A0A8, 0xFFFF6B8A,
      0xFF34C759, 0xFFFF453A,
      0xFFDC143C, 0xFFFF3B5C, 0xFF3A242A, 0xFF5A3038,
      0xFFDC143C, 0xFFFF3B5C,
      0xFF6A4A52, 0xFFDC143C, 0xFFFF3B5C, 0xFF48484F,
      false, 10, 12),

  LAVENDER(
      "Lavender",
      0xFF0F0C1A, 0xFF18122A,
      0x801800FF,
      0xCC1E182E, 0x33B794F6, 0x22B794F6, 0x14B794F6,
      0x0016161B, 0x001E1E26,
      0xFF221C34, 0xFFF0E8FF, 0xFF322B4A,
      0xFF2A2340, 0xFF322B4A, 0x33B794F6,
      0x22B794F6, 0xFFF0E8FF, 0xFFA89BC9, 0xFFB794F6,
      0xFF34C759, 0xFFFF453A,
      0xFF8B6CFF, 0xFFB794F6, 0xFF3D2E5E, 0xFF5A4A7A,
      0xFF8B6CFF, 0xFFB794F6,
      0xFF6A5A8A, 0xFF8B6CFF, 0xFF34C759, 0xFF48484F,
      false, 12, 12),

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
    if (s == null) return LIQUID_GLASS;
    try { return valueOf(s.toUpperCase()); } catch (Exception e) {
      if (s.equalsIgnoreCase("crimison") || s.equalsIgnoreCase("crimson")) return CRIMSON;
      if (s.equalsIgnoreCase("pastle") || s.equalsIgnoreCase("pastel")) return PASTEL;
      if (s.equalsIgnoreCase("amber")) return AMBER;
      if (s.equalsIgnoreCase("lavender")) return LAVENDER;
      if (s.equalsIgnoreCase("custom")) return CUSTOM;
      if (s.equalsIgnoreCase("liquid_glass") || s.equalsIgnoreCase("liquid glass")) return LIQUID_GLASS;
      return LIQUID_GLASS;
    }
  }

  public static int parseHex(String hex, int fallback) {
    if (hex == null) return fallback;
    String h = hex.trim().replace("#", "").replace("0x", "");
    if (h.length() == 6) h = "FF" + h;
    if (h.length() != 8) return fallback;
    try { return (int) Long.parseLong(h, 16); } catch (Exception e) { return fallback; }
  }
}
