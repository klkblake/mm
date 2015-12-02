package com.klkblake.mm.common;

import java.io.File;
import java.util.Arrays;

/**
 * Created by kyle on 6/10/15.
 */
public class SendingMessage {
    public final int type;
    public final String message;
    public final File[] photos; // TODO Strings or Files?
    public final long[] photoSizes;

    public SendingMessage(String message) {
        type = Message.TYPE_TEXT;
        this.message = message;
        photos = null;
        photoSizes = null;
    }

    public SendingMessage(File[] photos) {
        type = Message.TYPE_PHOTOS;
        this.photos = photos;
        photoSizes = new long[photos.length];
        Arrays.fill(photoSizes, -1);
        message = null;
    }
}
