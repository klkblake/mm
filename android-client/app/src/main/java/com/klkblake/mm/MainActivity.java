package com.klkblake.mm;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

public class MainActivity extends AppActivity {
    private Contact[] contacts = new Contact[] {
            new Contact("Test User", 0xffffcccc, null, new byte[32]),
            new Contact("Test User2", 0xffcc0033, null, new byte[32]),
    };

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
            return contacts.length;
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
            Contact contact = contacts[position];
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
