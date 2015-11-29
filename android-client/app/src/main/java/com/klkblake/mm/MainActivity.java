package com.klkblake.mm;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

public class MainActivity extends AppActivity {
    private AndroidUser[] contacts = new AndroidUser[] {
            new AndroidUser("Test User", 0xffffcccc, new byte[32], 0, null),
            new AndroidUser(),
            new AndroidUser("Test User2", 0xffcc0033, new byte[32], 0, null),
    };

    {
        contacts[1].addSubUser("Test User 1.1", 0xffffccff, new byte[32], 0, null);
        contacts[1].addSubUser("Test User 1.2", 0xffffffcc, new byte[32], 0, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final ListView contactsList = (ListView) findViewById(R.id.contactsList);
        contactsList.setAdapter(new ContactListAdapter());
        contactsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent intent = new Intent(App.context, ChatActivity.class);
                intent.putExtra(ChatActivity.EXTRA_CONTACT, contacts[position]);
                App.startActivity(intent);
            }
        });
    }

    private class ContactListAdapter extends ColoredListAdapter {
        @Override
        public int getCount() {
            int count = 0;
            for (AndroidUser user : contacts) {
                count += user.subusers.size();
            }
            return count;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AndroidUser.SubUser contact;
            for (int userIndex = 0; ; userIndex++) {
                AndroidUser user = contacts[userIndex];
                if (position < user.subusers.size()) {
                    contact = user.subusers.get(position);
                    break;
                }
                position -= user.subusers.size();
            }
            TextView view;
            if (convertView == null) {
                view = (TextView) getLayoutInflater().inflate(R.layout.item_contact, parent, false);
                initSelectableView(view, view.getBackground());
            } else {
                view = (TextView) convertView;
            }
            if (contact.hasAvatar()) {
                BitmapDrawable avatar = new BitmapDrawable(getResources(), contact.getAvatar());
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(avatar, null, null, null);
            } else {
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_person_48dp, 0, 0, 0);
            }
            view.setText(contact.getName());
            int color = contact.getColor();
            setSelectableBackgroundColor(view, color);
            return view;
        }
    }
}
