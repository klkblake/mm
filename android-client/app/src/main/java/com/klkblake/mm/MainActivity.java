package com.klkblake.mm;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.text.TextPaint;
import android.util.ArrayMap;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.klkblake.mm.common.Resources;

import java.util.ArrayList;
import java.util.Arrays;

import static com.klkblake.mm.common.Util.min;

public class MainActivity extends AppActivity {
    private static final int CONTACT_CHUNK_SIZE = 16;
    private final ArrayList<AndroidUser> contacts = new ArrayList<>();
    private Bitmap defaultAvatar;
    private Drawable selectableItemBackground;
    private TextPaint textPaintLargeName = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint textPaintSmallName = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint textPaintRecentMessage;

    {
        contacts.add(new AndroidUser("Test User", 0xffffcccc, new byte[32], 0, null));
        contacts.add(new AndroidUser("Test User", 0xffe2b449, new byte[32], 0, null));
        contacts.add(new AndroidUser("Test User", 0xffe1b27a, new byte[32], 0, null));
        AndroidUser user1 = new AndroidUser("Test User", 0xffffffff, new byte[32], 0, null);
        user1.addSubUser("Test User", 0xfffafafa, new byte[32], 0, null);
        user1.addSubUser("Test User", 0xffe2b449, new byte[32], 0, null);
        user1.addSubUser("Test User", 0xffe2b448, new byte[32], 0, null);
        user1.addSubUser("Test User", 0xff303030, new byte[32], 0, null);
        user1.addSubUser("Test User", 0xff000000, new byte[32], 0, null);
        user1.addSubUser("Test User", 0xffaabbcc, new byte[32], 0, null);
        contacts.add(user1);
        AndroidUser user2 = new AndroidUser("Test User 1.1", 0xffffccff, new byte[32], 0, null);
        user2.addSubUser("Test User 1.2", 0xff4400cc, new byte[32], 0, null);
        contacts.add(user2);
        contacts.add(new AndroidUser("Test User2", 0xffcc0033, new byte[32], 0, null));
        AndroidUser bobAndJane = new AndroidUser("Bob", 0xffff0000, new byte[32], 0, null);
        bobAndJane.addSubUser("Jane", 0xff0000ff, new byte[32], 0, null);
        contacts.add(bobAndJane);
        AndroidUser massive = new AndroidUser("Brace yourself, here it comes", 0xffff0080, new byte[32], 0, null);
        for (int i = 0; i < 255; i++) {
            massive.addSubUser("Subuser " + i, 0xffff8000 + i, new byte[32], 0, null);
        }
        contacts.add(massive);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        onCreate(savedInstanceState, R.layout.activity_main);

        TypedValue background = new TypedValue();
        getTheme().resolveAttribute(R.attr.selectableItemBackground, background, true);
        selectableItemBackground = getDrawable(background.resourceId);

        int[] attrs = new int[] {
                android.R.attr.textSize,
                android.R.attr.textColor,
                android.R.attr.fontFamily,
                android.R.attr.textStyle,
        };
        TypedValue appearance = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textAppearanceListItem, appearance, true);
        TypedArray ta = obtainStyledAttributes(appearance.resourceId, attrs);
        textPaintLargeName.setTextSize(ta.getDimensionPixelSize(0, -1));
        textPaintLargeName.setColor(ta.getColor(1, -1));
        Typeface tf = Typeface.create(ta.getString(2), ta.getInt(3, -1));
        textPaintLargeName.setTypeface(tf);
        ta.recycle();

        getTheme().resolveAttribute(android.R.attr.textAppearanceListItemSecondary, appearance, true);
        ta = obtainStyledAttributes(appearance.resourceId, attrs);
        textPaintSmallName.setTextSize(ta.getDimensionPixelSize(0, -1));
        textPaintSmallName.setColor(ta.getColor(1, -1));
        tf = Typeface.create(ta.getString(2), ta.getInt(3, -1));
        textPaintSmallName.setTypeface(tf);
        ta.recycle();

        textPaintRecentMessage = new TextPaint(textPaintSmallName);
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, appearance, true);
        textPaintRecentMessage.setColor(getResources().getColor(appearance.resourceId));

        Drawable defaultAvatarVector = getDrawable(R.drawable.ic_person_40dp);
        assert defaultAvatarVector != null;
        defaultAvatar = Bitmap.createBitmap(defaultAvatarVector.getIntrinsicWidth(), defaultAvatarVector.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(defaultAvatar);
        defaultAvatarVector.setBounds(0, 0, defaultAvatar.getWidth(), defaultAvatar.getHeight());
        defaultAvatarVector.draw(c);

        nextEmbeddedContact:
        for (byte[] key : Resources.EMBEDDED_CONTACTS) {
            for (AndroidUser contact : contacts) {
                if (Arrays.equals(contact.pubkey, key)) {
                    continue nextEmbeddedContact;
                }
            }
            contacts.add(new AndroidUser(key));
        }

        RecyclerView contactsList = (RecyclerView) findViewById(R.id.contactsList);
        contactsList.setHasFixedSize(true);
        contactsList.setLayoutManager(new LinearLayoutManager(this));
        contactsList.setAdapter(new ContactListAdapter());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            return true;
        } else if (id == R.id.action_send_contact_request) {
            Intent intent = new Intent(App.context, SendContactRequestActivity.class);
            App.startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private class ContactListAdapter extends RecyclerView.Adapter<ContactViewHolder> {
        private ArrayMap<AndroidUser, ArrayList<ContactView>> viewsForContact = new ArrayMap<>();
        @Override
        public ContactViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ContactView view = new ContactView(MainActivity.this, viewType, textPaintLargeName, textPaintSmallName, textPaintRecentMessage, defaultAvatar);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            view.setLayoutParams(params);
            view.setBackground(selectableItemBackground.mutate().getConstantState().newDrawable(getResources(), getTheme()));
            return new ContactViewHolder(view);
        }

        private int getItem(int position) {
            for (int i = 0; i < contacts.size(); i++) {
                AndroidUser contact = contacts.get(i);
                int chunks = (contact.subusers.size() - 1) / CONTACT_CHUNK_SIZE + 1;
                if (position >= chunks) {
                    position -= chunks;
                } else {
                    return i << 8 | position;
                }
            }
            throw new IndexOutOfBoundsException();
        }

        @Override
        public void onViewRecycled(ContactViewHolder holder) {
            ArrayList<ContactView> views = viewsForContact.get(holder.contact);
            ContactView view = (ContactView) holder.itemView;
            views.remove(view);
            if (views.size() == 0) {
                viewsForContact.remove(holder.contact);
            }
        }

        @Override
        public void onBindViewHolder(final ContactViewHolder holder, int position) {
            int packed = getItem(position);
            final AndroidUser contact = contacts.get(packed >> 8);
            int chunk = packed & 0xff;
            int offset = chunk * CONTACT_CHUNK_SIZE;
            boolean isLast = offset + CONTACT_CHUNK_SIZE >= contact.subusers.size();
            ArrayList<ContactView> views = viewsForContact.get(contact);
            if (views == null) {
                views = new ArrayList<>();
                viewsForContact.put(contact, views);
            }
            views.add((ContactView) holder.itemView);
            holder.bind(views, contact, offset, isLast);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(App.context, ChatActivity.class);
                    intent.putExtra(ChatActivity.EXTRA_CONTACT, contact);
                    App.startActivity(intent);
                }
            });
        }

        @Override
        public int getItemViewType(int position) {
            int packed = getItem(position);
            ArrayList<AndroidUser.SubUser> subusers = contacts.get(packed >> 8).subusers;
            int chunk = packed & 0xff;
            int offset = chunk * CONTACT_CHUNK_SIZE;
            return min(subusers.size() - offset, CONTACT_CHUNK_SIZE);
        }

        @Override
        public int getItemCount() {
            int total = 0;
            for (AndroidUser contact : contacts) {
                total += (contact.subusers.size() - 1) / CONTACT_CHUNK_SIZE + 1;
            }
            return total;
        }
    }

    private class ContactViewHolder extends ViewHolder {
        public AndroidUser contact;

        public ContactViewHolder(ContactView view) {
            super(view);
        }

        public void bind(ArrayList<ContactView> views, AndroidUser contact, int offset, boolean isLast) {
            this.contact = contact;
            ContactView view = (ContactView) itemView;
            view.bind(views, contact.subusers, offset, isLast);
            int dp2 = (int) (2 * App.density);
            int dp16 = (int) (16 * App.density);
            if (offset == 0 && isLast) {
                view.setPadding(dp16, dp16, dp16, dp16);
            } else if (offset == 0) {
                view.setPadding(dp16, dp16, dp16, dp2);
            } else if (isLast) {
                view.setPadding(dp16, 0, dp16, dp16);
            } else {
                view.setPadding(dp16, 0, dp16, dp2);
            }
        }
    }
}
