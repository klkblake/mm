package com.klkblake.mm;

import android.graphics.Bitmap;
import android.net.Uri;

import java.io.File;

/**
 * Created by kyle on 1/10/15.
 */
public class Message {
    public final long messageId;
    public final String author;
    public final MessageType type;
    public final String text;
    public final Bitmap[] photos;
    public final Uri[] photoUris;

    public Message(long messageId, String author, String text) {
        this.messageId = messageId;
        this.author = author;
        type = MessageType.TEXT;
        this.text = text;
        photos = null;
        photoUris = null;
    }

    public Message(long messageId, String author, Bitmap photo, Uri photoUri) {
        this.messageId = messageId;
        this.author = author;
        type = MessageType.PHOTO;
        text = null;
        photos = new Bitmap[] { photo };
        photoUris = new Uri[] { photoUri };
    }

    public Message(long messageId, String author, Bitmap[] photos, Uri[] photoUris) {
        this.messageId = messageId;
        this.author = author;
        type = MessageType.ALBUM;
        text = null;
        this.photos = photos;
        this.photoUris = photoUris;
    }
}
