package com.klkblake.mm;

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
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.klkblake.mm.common.Resources;

import java.util.ArrayList;
import java.util.Arrays;

import static com.klkblake.mm.common.Util.min;

public class MainActivity extends AppActivity {
    private static final int CONTACT_CHUNK_SIZE = 16;
    private final ArrayList<AndroidUser> contacts = new ArrayList<>();
    private RecyclerView contactsList;
    private Bitmap defaultAvatar;
    private Drawable selectableItemBackground;
    private TextPaint textPaint;
    private String namePairFormat;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TypedValue background = new TypedValue();
        getTheme().resolveAttribute(R.attr.selectableItemBackground, background, true);
        selectableItemBackground = getDrawable(background.resourceId);
        TypedValue appearance = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textAppearanceListItem, appearance, true);
        TextView v = new TextView(this);
        v.setTextAppearance(this, appearance.resourceId);
        textPaint = v.getPaint();
        namePairFormat = getString(R.string.name_pair_format);

        int[] attrs = new int[] {
                android.R.attr.textSize,
                android.R.attr.textColor,
                android.R.attr.fontFamily,
                android.R.attr.textStyle,
        };
        getTheme().resolveAttribute(android.R.attr.textAppearanceListItemSecondary, appearance, true);
        TypedArray ta = obtainStyledAttributes(appearance.resourceId, attrs);
        textPaintSmallName.setTextSize(ta.getDimensionPixelSize(0, -1));
        textPaintSmallName.setColor(ta.getColor(1, -1));
        Typeface tf = Typeface.create(ta.getString(2), ta.getInt(3, -1));
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

        contactsList = (RecyclerView) findViewById(R.id.contactsList);
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
        } else if (id == R.id.action_add_contact) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void setAvatarIfPresent(ImageView avatar, AndroidUser.SubUser subuser) {
        if (subuser.hasAvatar()) {
            avatar.setImageBitmap(subuser.getAvatar());
        } else {
            avatar.setImageBitmap(defaultAvatar);
        }
    }

    private class ContactListAdapter extends RecyclerView.Adapter<ContactViewHolder> {
        @Override
        public ContactViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            int layout = 0;
            if (viewType == 0) {
                layout = R.layout.item_contact_two;
            } else if (viewType == 1) {
                layout = R.layout.item_contact;
            }
            View view;
            if (layout != 0) {
                view = LayoutInflater.from(MainActivity.this).inflate(layout, parent, false);
            } else {
                view = new MultipleContactView(MainActivity.this, viewType, textPaintSmallName, textPaintRecentMessage, defaultAvatar);
            }
            view.setBackground(selectableItemBackground.getConstantState().newDrawable(getResources(), getTheme()));
            if (viewType == 0) {
                return new TwoContactViewHolder((ViewGroup) view);
            } else if (viewType == 1) {
                return new SingleContactViewHolder((ViewGroup) view);
            } else {
                return new MultiContactViewHolder((MultipleContactView) view);
            }
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
        public void onBindViewHolder(final ContactViewHolder holder, int position) {
            int packed = getItem(position);
            final AndroidUser contact = contacts.get(packed >> 8);
            int chunk = packed & 0xff;
            int offset = chunk * CONTACT_CHUNK_SIZE;
            boolean isLast = offset + CONTACT_CHUNK_SIZE > contact.subusers.size();
            holder.bind(contact.subusers, offset, isLast);
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
            int count = min(subusers.size() - offset, CONTACT_CHUNK_SIZE);
            if (count != 2) {
                return count;
            }
            String name1 = subusers.get(offset).getName();
            String name2 = subusers.get(offset + 1).getName();
            float width = StaticLayout.getDesiredWidth(name1 + " and " + name2, textPaint);
            float startMargin = 114 * App.density;
            float endMargin = 98 * App.density;
            if (width > contactsList.getWidth() - startMargin - endMargin) {
                return 2;
            } else {
                return 0;
            }
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

    private abstract class ContactViewHolder extends ViewHolder {
        public ContactViewHolder(View view) {
            super(view);
        }

        public abstract void bind(ArrayList<AndroidUser.SubUser> subusers, int offset, boolean isLast);
    }

    private class SingleContactViewHolder extends ContactViewHolder {
        private ImageView avatar;
        private TextView displayName;
        private TextView messageText;
        private ImageView colorCircle;

        public SingleContactViewHolder(ViewGroup view) {
            super(view);
            avatar = (ImageView) view.findViewById(R.id.avatar);
            displayName = (TextView) view.findViewById(R.id.displayName);
            colorCircle = (ImageView) view.findViewById(R.id.colorCircle);
            messageText = (TextView) view.findViewById(R.id.messageText);
        }

        public void bind(ArrayList<AndroidUser.SubUser> subusers, int offset, boolean isLast) {
            AndroidUser.SubUser subuser = subusers.get(0);
            setAvatarIfPresent(avatar, subuser);
            displayName.setText(subuser.getName());
            colorCircle.setColorFilter(subuser.getColor());
        }
    }

    private class TwoContactViewHolder extends ContactViewHolder {
        private ImageView avatar1;
        private ImageView avatar2;
        private TextView displayName;
        private ImageView colorCircle1;
        private ImageView colorCircle2;
        private TextView messageText;

        public TwoContactViewHolder(ViewGroup view) {
            super(view);
            avatar1 = (ImageView) view.findViewById(R.id.avatar1);
            avatar2 = (ImageView) view.findViewById(R.id.avatar2);
            displayName = (TextView) view.findViewById(R.id.displayName);
            colorCircle1 = (ImageView) view.findViewById(R.id.colorCircle1);
            colorCircle2 = (ImageView) view.findViewById(R.id.colorCircle2);
            messageText = (TextView) view.findViewById(R.id.messageText);
        }

        public void bind(ArrayList<AndroidUser.SubUser> subusers, int offset, boolean isLast) {
            AndroidUser.SubUser subuser1 = subusers.get(0);
            AndroidUser.SubUser subuser2 = subusers.get(1);
            setAvatarIfPresent(avatar1, subuser1);
            setAvatarIfPresent(avatar2, subuser2);
            displayName.setText(String.format(namePairFormat, subuser1.getName(), subuser2.getName()));
            colorCircle1.setColorFilter(subuser1.getColor());
            colorCircle2.setColorFilter(subuser2.getColor());
        }
    }

    private class MultiContactViewHolder extends ContactViewHolder {
        public MultiContactViewHolder(MultipleContactView view) {
            super(view);
        }

        public void bind(ArrayList<AndroidUser.SubUser> subusers, int offset, boolean isLast) {
            MultipleContactView view = (MultipleContactView) itemView;
            view.setSubusers(subusers, offset, isLast);
            int dp16 = (int) (16 * App.density);
            int dp18 = (int) (18 * App.density);
            if (offset == 0 && isLast) {
                view.setPadding(dp16, dp16, dp16, dp18);
            } else if (offset == 0) {
                view.setPadding(dp16, dp16, dp16, 0);
            } else if (isLast) {
                view.setPadding(dp16, 0, dp16, dp18);
            } else {
                view.setPadding(dp16, 0, dp16, 0);
            }
        }
    }
}
