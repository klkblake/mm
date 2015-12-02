package com.klkblake.mm;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

import static com.klkblake.mm.common.Util.isDark;

public class MainActivity extends AppActivity {
    private final AndroidUser[] contacts = new AndroidUser[]{
            new AndroidUser("Test User", 0xffffcccc, new byte[32], 0, null),
            new AndroidUser("Test User", 0xffe2b449, new byte[32], 0, null),
            new AndroidUser("Test User", 0xffe1b27a, new byte[32], 0, null),
            new AndroidUser("Test User", 0xffffffff, new byte[32], 0, null),
            new AndroidUser("Test User 1.1", 0xffffccff, new byte[32], 0, null),
            new AndroidUser("Test User2", 0xffcc0033, new byte[32], 0, null),
    };

    {
        contacts[3].addSubUser("Test User", 0xfffafafa, new byte[32], 0, null);
        contacts[3].addSubUser("Test User", 0xffe2b449, new byte[32], 0, null);
        contacts[3].addSubUser("Test User", 0xffe2b448, new byte[32], 0, null);
        contacts[3].addSubUser("Test User", 0xff303030, new byte[32], 0, null);
        contacts[3].addSubUser("Test User", 0xff000000, new byte[32], 0, null);
        contacts[4].addSubUser("Test User 1.2", 0xff4400cc, new byte[32], 0, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ListView contactsList = (ListView) findViewById(R.id.contactsList);
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
        public ContactListAdapter() {
            super(MainActivity.this);
        }

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
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            ArrayList<AndroidUser.SubUser> subusers = contacts[position].subusers;
            if (subusers.size() > 1) {
                return 2;
            }
            return subusers.get(0).isDark() ? 0 : 1;
        }

        private TextView newSubuserView(AndroidUser.SubUser subuser, ViewGroup parent) {
            TextView view = (TextView) inflaterForTheme(subuser.isDark()).inflate(R.layout.item_contact, parent, false);
            initSelectableView(view, view.getBackground());
            return view;
        }

        private void setupSubuserView(AndroidUser.SubUser subuser, TextView view) {
            if (subuser.hasAvatar()) {
                BitmapDrawable avatar = new BitmapDrawable(getResources(), subuser.getAvatar());
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(avatar, null, null, null);
            } else {
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_person_48dp, 0, 0, 0);
            }
            view.setText(subuser.getName());
            getSelectableBackgroundColor(view).setColor(subuser.getColor());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AndroidUser contact = contacts[position];
            int type = getItemViewType(position);
            if (type == 0 || type == 1) {
                TextView view;
                AndroidUser.SubUser subuser = contact.subusers.get(0);
                if (convertView == null) {
                    view = newSubuserView(subuser, parent);
                    bugfixPropagateHotspotChanges(view);
                } else {
                    view = (TextView) convertView;
                }
                setupSubuserView(subuser, view);
                return view;
            }
            LinearLayout view = null;
            if (convertView != null) {
                view = (LinearLayout) convertView;
                if (contact.subusers.size() == view.getChildCount()) {
                    for (int i = 0; i < contact.subusers.size(); i++) {
                        AndroidUser.SubUser subuser = contact.subusers.get(i);
                        TextView sview = (TextView) view.getChildAt(i);
                        if (isDark(getSelectableBackgroundColor(sview).getColor()) != isDark(subuser.getColor())) {
                            view = null;
                            break;
                        }
                    }
                } else {
                    view = null;
                }
            }
            if (view == null) {
                view = new LinearLayout(MainActivity.this);
                view.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                view.setOrientation(LinearLayout.VERTICAL);
                for (AndroidUser.SubUser subuser : contact.subusers) {
                    view.addView(newSubuserView(subuser, parent));
                }
                bugfixPropagateHotspotChanges(view);
                final LinearLayout view_ = view;
                view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        for (int i = 0; i < view_.getChildCount(); i++) {
                            TextView child = (TextView) view_.getChildAt(i);
                            RippleDrawable ripple = getSelectableBackgroundRipple(child);
                            ripple.setHotspotBounds(0, -child.getTop(), child.getWidth(), child.getHeight() + (view_.getHeight() - child.getBottom()));
                        }
                    }
                });
            }
            for (int i = 0; i < contact.subusers.size(); i++) {
                AndroidUser.SubUser subuser = contact.subusers.get(i);
                TextView sview = (TextView) view.getChildAt(i);
                setupSubuserView(subuser, sview);
            }
            return view;
        }
    }
}
