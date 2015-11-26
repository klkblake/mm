package com.klkblake.mm;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.support.v4.content.FileProvider;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;

import static com.klkblake.mm.Util.calculateSampleSize;

/**
 * Created by kyle on 5/10/15.
 */
public class App {
    public static Context context;
    public static Context activityContext;
    public static Resources resources;
    public static PackageManager packageManager;
    public static ContentResolver contentResolver;

    public static void onActivityCreate(Activity activity) {
        context = activity.getApplicationContext();
        resources = context.getResources();
        packageManager = context.getPackageManager();
        contentResolver = context.getContentResolver();
    }

    public static void onActivityResume(Activity activity) {
        activityContext = activity;
    }

    public static void onActivityPause() {
        activityContext = null;
    }

    public static Uri getUriForFile(File file) {
        return FileProvider.getUriForFile(context, "com.klkblake.mm.fileprovider", file);
    }

    public static Uri getUriForPath(String path) {
        return getUriForFile(new File(path));
    }

    public static boolean canStartActivity(Intent intent) {
        return intent.resolveActivity(packageManager) != null;
    }

    public static void startActivity(Intent intent) {
        activityContext.startActivity(intent);
    }

    public static void tryStartActivity(Intent intent) {
        if (canStartActivity(intent)) {
            startActivity(intent);
        }
    }

    public static Bitmap decodeSampledBitmap(String path, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        options.inSampleSize =
                calculateSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        return ThumbnailUtils.extractThumbnail(bitmap, reqWidth, reqHeight,
                ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
    }

    public static void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
