package com.paddiary.local;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BulletSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ParagraphStyle;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import org.json.JSONArray;
import org.json.JSONObject;

final class RichText {
    private RichText() { }

    static final class Heading extends MetricAffectingSpan implements ParagraphStyle {
        final int level;
        final float scale;
        Heading(int level, float scale) { this.level = level; this.scale = scale; }
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
        private void apply(TextPaint paint) {
            float sp = level == 1 ? 32 : level == 2 ? 27 : 23;
            paint.setTextSize(sp * scale * paint.density);
            paint.setTypeface(Typeface.create(paint.getTypeface(), Typeface.BOLD));
        }
    }

    static final class Numbered implements LeadingMarginSpan {
        final int number;
        final int margin;
        Numbered(int number, int margin) { this.number = number; this.margin = margin; }
        @Override public int getLeadingMargin(boolean first) { return margin; }
        @Override public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top,
                int baseline, int bottom, CharSequence text, int start, int end, boolean first, android.text.Layout layout) {
            if (first) canvas.drawText(number + ".", x + dir * 5, baseline, paint);
        }
    }

    static String encode(Spanned text, float scale) {
        JSONArray result = new JSONArray();
        Object[] spans = text.getSpans(0, text.length(), Object.class);
        for (Object span : spans) {
            String type; String value = "";
            if (span instanceof Heading) { type = "heading"; value = String.valueOf(((Heading)span).level); }
            else if (span instanceof Numbered) { type = "numbered"; value = String.valueOf(((Numbered)span).number); }
            else if (span instanceof BulletSpan) type = "bullet";
            else if (span instanceof StyleSpan) { type = "style"; value = String.valueOf(((StyleSpan)span).getStyle()); }
            else if (span instanceof TypefaceSpan) { type = "font"; value = ((TypefaceSpan)span).getFamily(); }
            else if (span instanceof AbsoluteSizeSpan) { type = "size"; value = String.valueOf(Math.round(((AbsoluteSizeSpan)span).getSize() / scale)); }
            else continue;
            int start = text.getSpanStart(span), end = text.getSpanEnd(span);
            if (start < 0 || end <= start) continue;
            JSONObject item = new JSONObject();
            try { item.put("t", type); item.put("s", start); item.put("e", end); item.put("v", value); result.put(item); }
            catch (Exception ignored) { }
        }
        return result.toString();
    }

    static void decode(Editable text, String json, float scale, float density) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray items = new JSONArray(json);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                int start = item.getInt("s"), end = item.getInt("e");
                if (start < 0 || end > text.length() || end <= start) continue;
                String type = item.getString("t"), value = item.optString("v", "");
                Object span; int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
                switch (type) {
                    case "heading": span = new Heading(Integer.parseInt(value), scale); flags = Spanned.SPAN_PARAGRAPH; break;
                    case "numbered": span = new Numbered(Integer.parseInt(value), (int)(38 * density)); flags = Spanned.SPAN_PARAGRAPH; break;
                    case "bullet": span = new BulletSpan((int)(12 * density)); flags = Spanned.SPAN_PARAGRAPH; break;
                    case "style": span = new StyleSpan(Integer.parseInt(value)); break;
                    case "font": span = new TypefaceSpan(value); break;
                    case "size": span = new AbsoluteSizeSpan((int)(Integer.parseInt(value) * scale), true); break;
                    default: continue;
                }
                if (flags == Spanned.SPAN_PARAGRAPH && (start > 0 && text.charAt(start - 1) != '\n' || end < text.length() && text.charAt(end - 1) != '\n')) continue;
                text.setSpan(span, start, end, flags);
            }
        } catch (Exception ignored) { }
    }

    static void applyInline(Editable text, int start, int end, Object span) {
        if (start >= end) return;
        text.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static void applyParagraph(Editable text, int start, int end, String kind, int value, float scale, float density) {
        if (text.length() == 0) return;
        int from = Math.min(text.length(), Math.max(0, start)), to = Math.min(text.length(), Math.max(from, end));
        while (from > 0 && text.charAt(from - 1) != '\n') from--;
        if (to == from && to < text.length()) to++;
        while (to < text.length() && text.charAt(to - 1) != '\n') to++;
        if (to <= from) return;
        for (Object span : text.getSpans(from, to, Object.class)) {
            if (span instanceof Heading || span instanceof Numbered || span instanceof BulletSpan) text.removeSpan(span);
        }
        if (kind.equals("plain")) return;
        int number = 1;
        for (int p = from; p < to;) {
            int e = p;
            while (e < text.length() && text.charAt(e) != '\n') e++;
            if (e < text.length()) e++;
            Object span = kind.equals("heading") ? new Heading(value, scale) :
                    kind.equals("bullet") ? new BulletSpan((int)(12 * density)) : new Numbered(number++, (int)(38 * density));
            text.setSpan(span, p, e, Spanned.SPAN_PARAGRAPH);
            p = e;
        }
    }
}
