package com.klkblake.mm;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.klkblake.mm.common.Resources;

import java.util.ArrayList;
import java.util.Arrays;

public class MainActivity extends AppActivity {
    private final ArrayList<AndroidUser> contacts = new ArrayList<>();
    private RecyclerView contactsList;
    private Drawable defaultAvatar;
    private Drawable circle;
    private Drawable selectableItemBackground;
    private TextPaint textPaint;
    private int textColorPrimary;
    private String namePairFormat;

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

        defaultAvatar = getDrawable(R.drawable.ic_person_40dp);
        circle = getDrawable(R.drawable.circle);
        TypedValue background = new TypedValue();
        getTheme().resolveAttribute(R.attr.selectableItemBackground, background, true);
        selectableItemBackground = getDrawable(background.resourceId);
        TypedValue appearance = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textAppearanceListItem, appearance, true);
        TextView v = new TextView(this);
        v.setTextAppearance(this, appearance.resourceId);
        textPaint = v.getPaint();
        TypedValue color = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, color, true);
        textColorPrimary = getResources().getColor(color.resourceId);
        namePairFormat = getString(R.string.name_pair_format);

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
            avatar.setImageDrawable(defaultAvatar);
        }
    }

    private class ContactListAdapter extends RecyclerView.Adapter<ContactViewHolder> {
        @Override
        public ContactViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            int layout;
            if (viewType == 0) {
                layout = R.layout.item_contact_two;
            } else if (viewType == 1) {
                layout = R.layout.item_contact;
            } else {
                layout = R.layout.item_contact_multiple;
            }
            ViewGroup view = (ViewGroup) LayoutInflater.from(MainActivity.this).inflate(layout, parent, false);
            view.setBackground(selectableItemBackground.getConstantState().newDrawable(getResources(), getTheme()));
            if (viewType == 0) {
                return new TwoContactViewHolder(view);
            } else if (viewType == 1) {
                return new SingleContactViewHolder(view);
            } else {
                return new MultiContactViewHolder(view, viewType);
            }
        }

        @Override
        public void onBindViewHolder(final ContactViewHolder holder, int position) {
            final AndroidUser contact = contacts.get(position);
            holder.bind(contact.subusers);
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
            ArrayList<AndroidUser.SubUser> subusers = contacts.get(position).subusers;
            int count = subusers.size();
            if (count != 2) {
                return count;
            }
            String name1 = subusers.get(0).getName();
            String name2 = subusers.get(1).getName();
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
            return contacts.size();
        }
    }

    private abstract class ContactViewHolder extends ViewHolder {
        public ContactViewHolder(ViewGroup view) {
            super(view);
        }

        public abstract void bind(ArrayList<AndroidUser.SubUser> subusers);
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

        public void bind(ArrayList<AndroidUser.SubUser> subusers) {
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

        public void bind(ArrayList<AndroidUser.SubUser> subusers) {
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
        private ImageView[] avatars;
        private TextView[] displayNames;
        private ImageView[] colorCircles;
        private TextView messageText;

        public MultiContactViewHolder(ViewGroup view, int count) {
            super(view);
            avatars = new ImageView[count];
            displayNames = new TextView[count];
            colorCircles = new ImageView[count];
            int rowCount = count >> 1;
            boolean odd = false;
            if ((count & 1) == 1) {
                rowCount++;
                odd = true;
            }
            messageText = (TextView) view.findViewById(R.id.messageText);
            view.removeView(messageText);
            int dp40 = (int) (40 * App.density);
            for (int i = 0, j = 0; i < rowCount; i++) {
                // This is expanded for performance. See item_contact_row.xml for the original.
                RelativeLayout row = new RelativeLayout(MainActivity.this);
                LinearLayout.LayoutParams llParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * App.density));
                row.setLayoutParams(llParams);

                RelativeLayout.LayoutParams params;

                {
                    ImageView avatar1 = new ImageView(MainActivity.this);
                    params = new RelativeLayout.LayoutParams(dp40, dp40);
                    params.addRule(RelativeLayout.ALIGN_PARENT_START);
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.setMarginStart((int) (16 * App.density));
                    avatar1.setLayoutParams(params);
                    avatar1.setId(R.id.avatar1);
                    // XXX should we set a drawable for it here? Probably not...
                    row.addView(avatar1);
                    avatars[j] = avatar1;
                }

                {
                    ImageView avatar2 = new ImageView(MainActivity.this);
                    params = new RelativeLayout.LayoutParams(dp40, dp40);
                    params.addRule(RelativeLayout.END_OF, R.id.avatar1);
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.setMarginStart((int) (2 * App.density));
                    avatar2.setLayoutParams(params);
                    avatar2.setId(R.id.avatar2);
                    row.addView(avatar2);
                    if (j + 1 == count) {
                        avatar2.setVisibility(View.INVISIBLE);
                    } else {
                        avatars[j + 1] = avatar2;
                    }
                }

                {
                    ImageView circle2 = new ImageView(MainActivity.this);
                    params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.addRule(RelativeLayout.ALIGN_PARENT_END);
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.topMargin = (int) (8 * App.density);
                    params.setMarginEnd((int) (16 * App.density));
                    circle2.setLayoutParams(params);
                    circle2.setId(R.id.colorCircle2);
                    circle2.setImageDrawable(circle.getConstantState().newDrawable(getResources(), getTheme()));
                    row.addView(circle2);
                    if (j + 1 == count) {
                        circle2.setVisibility(View.INVISIBLE);
                    } else {
                        colorCircles[j + 1] = circle2;
                    }
                }

                {
                    ImageView circle1 = new ImageView(MainActivity.this);
                    params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.addRule(RelativeLayout.START_OF, R.id.colorCircle2);
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.topMargin = (int) (8 * App.density);
                    params.setMarginEnd((int) (18 * App.density));
                    circle1.setLayoutParams(params);
                    circle1.setId(R.id.colorCircle1);
                    circle1.setImageDrawable(circle.getConstantState().newDrawable(getResources(), getTheme()));
                    row.addView(circle1);
                    colorCircles[j] = circle1;
                }

                {
                    TextView displayName1 = new TextView(MainActivity.this);
                    params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.addRule(RelativeLayout.END_OF, R.id.avatar2);
                    params.addRule(RelativeLayout.START_OF, R.id.colorCircle1);
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.setMarginStart((int) (16 * App.density));
                    params.setMarginEnd((int) (16 * App.density));
                    displayName1.setLayoutParams(params);
                    // We only set the diff between the target style and the default
                    displayName1.setTextColor(textColorPrimary);
                    displayName1.setMaxLines(1);
                    displayName1.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(displayName1);
                    displayNames[j] = displayName1;
                }

                {
                    TextView displayName2 = new TextView(MainActivity.this);
                    params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.addRule(RelativeLayout.END_OF, R.id.avatar2);
                    params.addRule(RelativeLayout.START_OF, R.id.colorCircle1);
                    params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    params.topMargin = (int) (21 * App.density);
                    params.setMarginStart((int) (16 * App.density));
                    params.setMarginEnd((int) (16 * App.density));
                    displayName2.setLayoutParams(params);
                    // We only set the diff between the target style and the default
                    displayName2.setTextColor(textColorPrimary);
                    displayName2.setMaxLines(1);
                    displayName2.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(displayName2);
                    if (j + 1 == count) {
                        // TODO don't build a view we won't even show
                        displayName2.setVisibility(View.GONE);
                        messageText.setLayoutParams(displayName2.getLayoutParams());
                        row.addView(messageText);
                    } else {
                        displayNames[j + 1] = displayName2;
                    }
                }

                view.addView(row);
                j += 2;
            }
            if (!odd) {
                view.addView(messageText);
            }
        }

        public void bind(ArrayList<AndroidUser.SubUser> subusers) {
            for (int i = 0; i < subusers.size(); i++) {
                AndroidUser.SubUser subuser = subusers.get(i);
                setAvatarIfPresent(avatars[i], subuser);
                displayNames[i].setText(subuser.getName());
                colorCircles[i].setColorFilter(subuser.getColor());
            }
        }
    }
}
