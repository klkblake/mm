package com.klkblake.mm.common;

/**
 * Created by kyle on 23/10/15.
 */
public class Message {
    public static final boolean AUTHOR_US = true;
    public static final boolean AUTHOR_THEM = false;
    public static final int TYPE_PENDING = 0; // XXX remove?
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_PHOTOS = 2; // Must be last (see MessageListAdapter#getViewTypeCount())
    private final int id;
    private final long timestamp;
    public final boolean author;
    public final int type;
    public final String text;
    public final short photoCount;

    public Message(int id, long timestamp, boolean author, String text) {
        this.id = id;
        this.timestamp = timestamp;
        this.author = author;
        type = TYPE_TEXT;
        this.text = text;
        photoCount = -1;
    }

    public Message(int id, long timestamp, boolean author, short photoCount) {
        this.id = id;
        this.timestamp = timestamp;
        this.author = author;
        type = TYPE_PHOTOS;
        this.photoCount = photoCount;
        text = null;
    }
}
