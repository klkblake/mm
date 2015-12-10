package com.klkblake.mm;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;

/**
 * Created by kyle on 5/10/15.
 */
@SuppressLint("Registered")
public class AppActivity extends AppCompatActivity {
    protected void onCreate(Bundle savedInstanceState, int layout) {
        super.onCreate(savedInstanceState);
        App.onActivityCreate(this);
        setContentView(layout);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
        if (layout != R.layout.activity_main) {
            ActionBar actionBar = getSupportActionBar();
            assert actionBar != null;
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
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
