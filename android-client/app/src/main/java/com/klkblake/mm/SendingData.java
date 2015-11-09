package com.klkblake.mm;

import java.io.File;
import java.nio.channels.FileChannel;

import static com.klkblake.mm.Session.MAX_CHUNK_SIZE;

/**
 * Created by kyle on 23/10/15.
 */
public class SendingData implements Comparable<SendingData> {
    public final long messageID;
    public final int partID;
    public final File photo;
    public final long photoSize;
    public final boolean isSingle;

    public int chunksSent = 0;
    public FileChannel channel;

    public SendingData(long messageID, int partID, File photo, long photoSize, boolean isSingle) {
        this.messageID = messageID;
        this.partID = partID;
        this.photo = photo;
        this.photoSize = photoSize;
        this.isSingle = isSingle;
    }

    @Override
    public int compareTo(SendingData another) {
        return Long.compare(photoSize - chunksSent * MAX_CHUNK_SIZE, another.photoSize - another.chunksSent * MAX_CHUNK_SIZE);
    }
}
