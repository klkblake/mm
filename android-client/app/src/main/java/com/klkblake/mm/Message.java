package com.klkblake.mm;

import android.graphics.Bitmap;
import android.net.Uri;

/**
 * Created by kyle on 1/10/15.
 */
public class Message {
    public static final int AUTHOR_US = 0;
    public static final int AUTHOR_THEM = 1;
    public final long messageId;
    public final int author;
    public final MessageType type;
    public final String text;
    public final Bitmap[] photos;
    public final Uri[] photoUris;

    public Message(long messageId, int author, String text) {
        this.messageId = messageId;
        this.author = author;
        type = MessageType.TEXT;
        this.text = text;
        photos = null;
        photoUris = null;
    }

    public Message(long messageId, int author, Bitmap photo, Uri photoUri) {
        this(messageId, author, new Bitmap[] { photo }, new Uri[] { photoUri });
    }

    public Message(long messageId, int author, Bitmap[] photos, Uri[] photoUris) {
        this.messageId = messageId;
        this.author = author;
        type = MessageType.PHOTOS;
        text = null;
        this.photos = photos;
        this.photoUris = photoUris;
    }
}
