package com.klkblake.mm;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.support.v4.content.FileProvider;
import android.view.View;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * Created by kyle on 5/10/15.
 */
public class App {
    public static Context context;
    public static Resources resources;
    public static PackageManager packageManager;
    public static ContentResolver contentResolver;

    public static void onActivityCreate(Activity activity) {
        context = activity.getApplicationContext();
        resources = context.getResources();
        packageManager = context.getPackageManager();
        contentResolver = context.getContentResolver();
    }

    public static Uri getUriForFile(File file) {
        return FileProvider.getUriForFile(context, "com.klkblake.mm.fileprovider", file);
    }

    public static boolean canStartActivity(Intent intent) {
        return intent.resolveActivity(packageManager) != null;
    }

    public static void startActivity(Intent intent) {
        context.startActivity(intent);
    }

    public static void tryStartActivity(Intent intent) {
        if (canStartActivity(intent)) {
            startActivity(intent);
        }
    }

    public static InputStream openInputStream(Uri uri) throws FileNotFoundException {
        return contentResolver.openInputStream(uri);
    }
}
