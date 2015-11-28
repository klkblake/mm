package com.klkblake.mm;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class MainActivity extends AppActivity {
    private Contact[] contacts = new Contact[] {
            new Contact("Test User", 0xffffcccc, null, new byte[32]),
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
            View view;
            if (convertView == null) {
                view = getLayoutInflater().inflate(R.layout.item_contact, parent, false);
            } else {
                view = convertView;
            }
            ImageView avatarView = (ImageView) view.findViewById(R.id.avatarView);
            TextView contactNameView = (TextView) view.findViewById(R.id.contactNameView);
            if (contact.hasAvatar()) {
                avatarView.setImageBitmap(contact.getAvatar());
            } else {
                avatarView.setImageResource(R.drawable.ic_person_48dp);
            }
            contactNameView.setText(contact.getName());
            int color = contact.getColor();
            view.setBackgroundColor(color);
            setTextViewForBackground(contactNameView, color);
            return view;
        }
    }
}
