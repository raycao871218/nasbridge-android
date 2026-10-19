package com.nas.dashboard;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.ShapeDrawable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Small repeating paper/wax tiles, cached once rather than regenerated while scrolling. */
final class CrayonTexture {
    private static final Map<String, Bitmap> TILES = new LinkedHashMap<String, Bitmap>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> entry) { return size() > 16; }
    };

    static Shader shader(int color, float density, boolean wax) {
        int size = Math.max(64, Math.round(96 * density));
        String key = color + ":" + size + ":" + wax;
        Bitmap tile;
        synchronized (TILES) {
            tile = TILES.get(key);
            if (tile == null) {
                tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                int[] pixels = new int[size * size];
                Random random = new Random(color * 31L + (wax ? 7 : 0));
                for (int i = 0; i < pixels.length; i++) {
                    // Fine paper tooth breaks up pigment; text is drawn separately and stays crisp.
                    float grain = random.nextFloat();
                    float paper = wax ? (grain < .13f ? .66f : grain < .36f ? .30f : .06f) : 0;
                    int noise = wax ? 0 : random.nextInt(9) - 4;
                    pixels[i] = Color.rgb(channel(Color.red(color), paper, noise), channel(Color.green(color), paper, noise), channel(Color.blue(color), paper, noise));
                }
                tile.setPixels(pixels, 0, size, 0, 0, size, size);
                Canvas canvas = new Canvas(tile); Paint fiber = new Paint(Paint.ANTI_ALIAS_FLAG);
                fiber.setStrokeCap(Paint.Cap.ROUND);
                if (wax) {
                    fiber.setColor(0x25fffdf3); fiber.setStrokeWidth(Math.max(1, density * .65f));
                    // Short, overlapping diagonal wax strokes rather than a smooth flat fill.
                    for (int i = 0; i < 160; i++) {
                        float x = random.nextFloat() * size, y = random.nextFloat() * size;
                        canvas.drawLine(x, y, x + density * (5 + random.nextInt(16)), y - density * 3, fiber);
                    }
                } else {
                    fiber.setColor(0x0d8c7756); fiber.setStrokeWidth(Math.max(.5f, density * .35f));
                    for (int i = 0; i < 110; i++) {
                        float x = random.nextFloat() * size, y = random.nextFloat() * size;
                        canvas.drawLine(x, y, x + density * (1 + random.nextInt(4)), y + density, fiber);
                    }
                }
                TILES.put(key, tile);
            }
        }
        return new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    static ShapeDrawable paper(int color, float density) {
        ShapeDrawable paper = new ShapeDrawable(); paper.getPaint().setShader(shader(color, density, false)); return paper;
    }

    private static int channel(int value, float paper, int noise) { return Math.max(0, Math.min(255, Math.round(value + (255 - value) * paper) + noise)); }
}
