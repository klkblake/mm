package com.klkblake.mm;

import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Created by kyle on 28/11/15.
 */
public class Contact implements Parcelable {
    public final byte[] pubkey = new byte[32];

    private String name;
    private int color;
    private Bitmap avatar;
    private byte[] avatarSHA256 = new byte[32];

    public Contact(String name, int color, Bitmap avatar, byte[] avatarSHA256) {
        this.name = name;
        this.color = color;
        this.avatar = avatar;
        this.avatarSHA256 = avatarSHA256;
    }

    protected Contact(Parcel in) {
        in.readByteArray(pubkey);
        name = in.readString();
        color = in.readInt();
        avatar = in.readParcelable(Bitmap.class.getClassLoader());
        in.readByteArray(avatarSHA256);
    }

    public boolean hasAvatar() {
        return avatar != null;
    }

    public Bitmap getAvatar() {
        return avatar;
    }

    public String getName() {
        return name;
    }

    public int getColor() {
        return color;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(pubkey);
        dest.writeString(name);
        dest.writeInt(color);
        dest.writeParcelable(avatar, 0);
        dest.writeByteArray(avatarSHA256);
    }

    @SuppressWarnings("unused")
    public static final Parcelable.Creator<Contact> CREATOR = new Parcelable.Creator<Contact>() {
        @Override
        public Contact createFromParcel(Parcel in) {
            return new Contact(in);
        }

        @Override
        public Contact[] newArray(int size) {
            return new Contact[size];
        }
    };
}
