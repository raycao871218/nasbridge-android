package com.nas.dashboard;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;

/** Cached wax pigment with a gently uneven outline. No per-frame bitmap allocations. */
public class CrayonDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final boolean paper;
    private final int pigment;
    private final boolean wax;
    private final float density = Resources.getSystem().getDisplayMetrics().density;
    public CrayonDrawable() { this(0xfffffcf4, false, false); }
    protected CrayonDrawable(int color, boolean wax, boolean paper) {
        this.paper = paper;
        this.pigment = color;
        this.wax = wax;
        fill.setShader(CrayonTexture.shader(color, density, wax));
        edge.setColor(0xff9b929e); edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density * 1.2f); edge.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override protected void onBoundsChange(Rect b) {
        float l=b.left+density, t=b.top+density, r=b.right-density, bot=b.bottom-density;
        float c=Math.min(17*density, Math.min(b.width(),b.height())/4f);
        outline.reset(); outline.moveTo(l+c,t+density);
        outline.lineTo(r-c,t); outline.quadTo(r,t,r, t+c);
        outline.lineTo(r-density,bot-c); outline.quadTo(r,bot,r-c,bot);
        outline.lineTo(l+c,bot-density); outline.quadTo(l,bot,l,bot-c);
        outline.lineTo(l+density,t+c); outline.quadTo(l,t,l+c,t+density); outline.close();
    }
    @Override public void draw(Canvas canvas) {
        if(paper) canvas.drawRect(getBounds(),fill);
        else { canvas.drawPath(outline,fill); canvas.drawPath(outline,edge); }
    }
    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); edge.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { fill.setColorFilter(filter); edge.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    // RippleDrawable copies its children through ConstantState. Without one it reuses
    // this instance, so a wider button overwrites the path/bounds of a narrower one.
    @Override public ConstantState getConstantState() {
        final int color = pigment;
        final boolean waxTexture = wax, paperSurface = paper;
        final int configurations = getChangingConfigurations();
        return new ConstantState() {
            @Override public Drawable newDrawable() {
                return new CrayonDrawable(color, waxTexture, paperSurface);
            }
            @Override public int getChangingConfigurations() { return configurations; }
        };
    }
}
