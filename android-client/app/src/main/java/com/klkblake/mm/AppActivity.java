package com.klkblake.mm;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

/**
 * Created by kyle on 5/10/15.
 */
@SuppressLint("Registered")
public class AppActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        App.onActivityCreate(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.onActivityResume(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.onActivityPause();
    }
}
