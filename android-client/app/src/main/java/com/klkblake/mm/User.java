package com.klkblake.mm;


import com.klkblake.mm.common.Util;

import java.util.ArrayList;

/**
 * Created by kyle on 30/11/15.
 */
public class User<T> {
    public final byte[] pubkey = new byte[32];
    public final ArrayList<SubUser> subusers = new ArrayList<>();

    protected User() {
    }

    public User(String name, int color, byte[] avatarSHA256, int avatarSize, T avatar) {
        addSubUser(name, color, avatarSHA256, avatarSize, avatar);
    }

    public void addSubUser(String name, int color, byte[] avatarSHA256, int avatarSize, T avatar) {
        subusers.add(new SubUser(name, color, avatarSHA256, avatarSize, avatar));
    }

    class SubUser {
        protected String name;
        protected int color;
        protected byte[] avatarSHA256;
        protected int avatarSize;
        protected T avatar;

        public SubUser(String name, int color, byte[] avatarSHA256, int avatarSize, T avatar) {
            this.name = name;
            this.color = color;
            this.avatarSHA256 = avatarSHA256;
            this.avatarSize = avatarSize;
            this.avatar = avatar;
        }

        public String getName() {
            return name;
        }

        public int getColor() {
            return color;
        }

        public boolean isDark() {
            return Util.isDark(color);
        }

        public boolean hasAvatar() {
            return avatar != null;
        }

        public T getAvatar() {
            return avatar;
        }
    }
}
