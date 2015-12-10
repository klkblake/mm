package com.klkblake.mm;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;

import com.klkblake.mm.common.Resources;

public class SendContactRequestActivity extends AppActivity {

    @SuppressLint("MissingSuperCall")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        onCreate(savedInstanceState, R.layout.activity_send_contact_request);
    }

    public void sendContactRequest(View view) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        String key = Base64.encodeToString(Resources.PUBLIC_KEY, Base64.URL_SAFE | Base64.NO_WRAP);
        intent.putExtra(Intent.EXTRA_TEXT, "Add me as a contact on MM: http://klkblake.com/mm/contacts/add/" + key);
        App.startActivity(Intent.createChooser(intent, "Send a contact request"));
    }
}
