package com.klkblake.mm;

import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

import com.klkblake.mm.common.User;

/**
 * Created by kyle on 28/11/15.
 */
public class AndroidUser extends User<Bitmap> implements Parcelable {
    public AndroidUser(byte[] pubkey) {
        super(pubkey);
    }

    public AndroidUser(String name, int color, byte[] avatarSHA256, int avatarSize, Bitmap avatar) {
        super(name, color, avatarSHA256, avatarSize, avatar);
    }

    private AndroidUser(Parcel in) {
        in.readByteArray(pubkey);
        int count = in.readInt();
        subusers.ensureCapacity(count);
        for (int i = 0; i < count; i++) {
            String name = in.readString();
            int color = in.readInt();
            byte[] avatarSHA256 = new byte[32];
            in.readByteArray(avatarSHA256);
            int avatarSize = in.readInt();
            Bitmap avatar = in.readParcelable(Bitmap.class.getClassLoader());
            addSubUser(name, color, avatarSHA256, avatarSize, avatar);
        }
        if (count > 1) {
            subusers.trimToSize();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(pubkey);
        dest.writeInt(subusers.size());
        for (SubUser subuser : subusers) {
            dest.writeString(subuser.getName());
            dest.writeInt(subuser.getColor());
            dest.writeByteArray(subuser.getAvatarSHA256());
            dest.writeInt(subuser.getAvatarSize());
            dest.writeParcelable(subuser.getAvatar(), 0);
        }
    }

    @SuppressWarnings("unused")
    public static final Parcelable.Creator<AndroidUser> CREATOR = new Parcelable.Creator<AndroidUser>() {
        @Override
        public AndroidUser createFromParcel(Parcel in) {
            return new AndroidUser(in);
        }

        @Override
        public AndroidUser[] newArray(int size) {
            return new AndroidUser[size];
        }
    };
}
