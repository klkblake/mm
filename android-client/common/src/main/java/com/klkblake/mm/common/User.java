package com.klkblake.mm.common;

import java.util.ArrayList;
import java.util.Random;

/**
 * Created by kyle on 30/11/15.
 */
public class User<T> {
    public final byte[] pubkey;
    public final ArrayList<SubUser> subusers = new ArrayList<>(1);

    protected User() {
        pubkey = new byte[Crypto.KEYBYTES];
    }

    public User(String name, int color, byte[] avatarSHA256, int avatarSize, T avatar) {
        pubkey = new byte[Crypto.KEYBYTES];
        addSubUser(name, color, avatarSHA256, avatarSize, avatar);
    }

    public User(byte[] pubkey) {
        this.pubkey = pubkey;
        StringBuilder sb = new StringBuilder(pubkey.length * 2 + 2);
        sb.append('<');
        for (byte b : pubkey) {
            int top = b >> 4 & 0xf;
            int bottom = b & 0xf;
            if (bottom > 9) {
                sb.append((char)('a' + bottom - 10));
            } else {
                sb.append((char)('0' + bottom));
            }
            if (top > 9) {
                sb.append((char)('a' + top - 10));
            } else {
                sb.append((char)('0' + top));
            }
        }
        sb.append('>');
        Random rng = new Random();
        int color = rng.nextInt(0xffffff) | 0xff000000;
        addSubUser(sb.toString(), color, new byte[32], 0, null);
    }

    public void addSubUser(String name, int color, byte[] avatarSHA256, int avatarSize, T avatar) {
        subusers.add(new SubUser(name, color, avatarSHA256, avatarSize, avatar));
    }

    public class SubUser {
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

        public byte[] getAvatarSHA256() {
            return avatarSHA256;
        }

        public int getAvatarSize() {
            return avatarSize;
        }

        public T getAvatar() {
            return avatar;
        }
    }
}
