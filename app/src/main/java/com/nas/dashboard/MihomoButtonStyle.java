package com.nas.dashboard;

import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;

/** Each button owns its outline; avoid framework elevation and baseline clipping in rows. */
final class MihomoButtonStyle {
    private MihomoButtonStyle() {}
    static void apply(Button button,boolean selected) {
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28657da9),
            new CrayonDrawable(selected?0xffd7e6d3:0xfffffcf4,false,false),null));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setTranslationZ(0);
        button.setSelected(selected);
    }
}
