package com.klkblake.mm;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.support.v4.content.FileProvider;

import java.io.File;

/**
 * Created by kyle on 5/10/15.
 */
public class Util {
    public static float perceivedBrightness(int color) {
        float r = ((color >> 16) & 0xff) / 255.0f;
        float g = ((color >> 8) & 0xff) / 255.0f;
        float b = (color & 0xff) / 255.0f;
        return (float) Math.sqrt(0.299*r*r + 0.587*g*g + 0.114*b*b);
    }

    public static Uri getUriForFile(Context context, File file) {
        return FileProvider.getUriForFile(context, "com.klkblake.mm.fileprovider", file);
    }
}
