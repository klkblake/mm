package com.klkblake.mm;

import com.klkblake.mm.common.Util;

import java.io.File;
import java.util.ArrayList;

import static com.klkblake.mm.Failure.*;

public class Storage {
    // TODO Always have all past messages loaded
    public static final int TYPE_SHIFT = 29;
    public static final int INDEX_MASK = (1 << TYPE_SHIFT) - 1;
    // TODO implement a disk cache
    // TODO always have metadata for *all* previous messages stored
    final String photosDir;
    // FIXME signed/unsigned bugs. probably store compressed here, and expand in use?
    LongArray timestamps = new LongArray();
    BooleanArray authors = new BooleanArray();
    IntArray indexes = new IntArray();
    // Type 0 - Pending content
    // TODO implement pending data
    // Type 1 - Text
    ArrayList<byte[]> texts = new ArrayList<>(); // TODO manual string alloc?
    // Type 2 - Photos
    ShortArray photoCounts = new ShortArray();

    public Storage(String photosDir) {
        this.photosDir = photosDir;
    }

    private void storeMessage(long messageID, long timestamp, boolean author, int type, int index) throws AssertionFailure {
        // XXX fix this messageID cast nonsense
        if (messageID > Integer.MAX_VALUE) {
            throw new AssertionFailure("message ID too big");
        }
        timestamps.set((int) messageID, timestamp);
        authors.set((int) messageID, author);
        indexes.set((int) messageID, type << TYPE_SHIFT | index & INDEX_MASK);
    }

    synchronized void storeMessage(long messageID, long timestamp, boolean author, String message) throws AssertionFailure {
        texts.add(Util.utf8Encode(message));
        storeMessage(messageID, timestamp, author, Message.TYPE_TEXT, texts.size() - 1);
    }

    synchronized void storePhotos(long messageID, long timestamp, boolean author, short photoCount) throws AssertionFailure {
        photoCounts.add(photoCount);
        storeMessage(messageID, timestamp, author, Message.TYPE_PHOTOS, photoCounts.count - 1);
    }

    void storePhoto(SendingData data) throws FilesystemFailure {
        File file;
        if (data.isSingle) {
            file = new File(photosDir, data.messageID + ".jpg");
        } else {
            File dir = new File(photosDir, Long.toString(data.messageID));
            if (!dir.mkdir() && !dir.isDirectory()) {
                throw new FilesystemFailure("Could not create directory " + dir.getAbsolutePath());
            }
            file = new File(dir, data.partID + ".jpg");
        }
        if (!data.photo.renameTo(file)) {
            throw new FilesystemFailure("Could not move file " + file);
        }
    }

    public synchronized Message getMessage(int id) {
        // XXX threading? Is this remotely safe?
        long timestamp = timestamps.data[id];
        boolean author = authors.data[id];
        int tyindex = indexes.data[id];
        int type = tyindex >> TYPE_SHIFT;
        int index = tyindex & INDEX_MASK;
        if (type == Message.TYPE_TEXT) {
            return new Message(id, timestamp, author, Util.utf8Decode(texts.get(index)));
        } else if (type == Message.TYPE_PHOTOS) {
            return new Message(id, timestamp, author, photoCounts.data[index]);
        }
        throw new AssertionError("Unhandled type in getMessage");
    }

    public synchronized int getMessageCount() {
        return timestamps.count;
    }
}