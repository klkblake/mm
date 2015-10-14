package com.klkblake.mm;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.klkblake.mm.Util.utf8Decode;
import static com.klkblake.mm.Util.utf8Encode;

public final class MessageService extends Service implements Runnable {
    public static final int TYPE_SHIFT = 29;
    public static final int INDEX_MASK = (1 << TYPE_SHIFT) - 1;
    public static final int TYPE_PENDING = 0;
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_PHOTOS = 2; // Must be last

    private Thread mainThread;
    private boolean die;
    private Binder binder;

    // These are package-access so we can cheaply access them from the inner class.
    String photosDir;

    int messageIDStart;
    LongArray timestamps = new LongArray();
    BooleanArray authors = new BooleanArray();
    IntArray indexes = new IntArray();
    // Type 0 - Pending content
    // TODO implement pending data
    // Type 1 - Text
    ArrayList<byte[]> texts = new ArrayList<>(); // TODO manual string alloc
    // Type 2 - Photos
    ShortArray photoCounts = new ShortArray();

    final Object monitor = new Object();
    ConcurrentLinkedQueue<MessageListAdapter> newAdapters = new ConcurrentLinkedQueue<>();
    // Accessed by multiple threads
    ArrayList<MessageListAdapter> adapters = new ArrayList<>();
    // XXX dummy data for now
    ConcurrentLinkedQueue<Boolean> messagesToSend = new ConcurrentLinkedQueue<>();

    @Override
    public void onCreate() {
        photosDir = getCacheDir() + "/photos";
        die = false;
        mainThread = new Thread(this, "Main service thread");
        mainThread.start();

        binder = new Binder();
        Random r = new Random();
        for (int i = 0; i < 100; i++) {
            timestamps.add(System.currentTimeMillis());
            authors.add(Message.AUTHOR_US);
            indexes.add(TYPE_TEXT << MessageService.TYPE_SHIFT | (texts.size()) & MessageService.INDEX_MASK);
            texts.add(utf8Encode(i + ": " + r.nextInt()));
        }
        binder.sendMessage("How are you?");
        binder.sendMessage("I am good, thank you! I slept very well");
        binder.sendMessage("How about you?");
        binder.sendMessage("I slept reasonably well too.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void run() {
        while (true) {
            if (die) {
                break;
            }
            for (MessageListAdapter adapter; (adapter = newAdapters.poll()) != null;) {
                adapter.updateLoadedRange(messageIDStart, timestamps.count);
                adapters.add(adapter);
            }
            boolean loadedRangeDirty = false;
            for (Boolean message; (message = messagesToSend.poll()) != null;) {
                loadedRangeDirty = true;
            }
            if (loadedRangeDirty) {
                for (MessageListAdapter adapter : adapters) {
                    adapter.updateLoadedRange(messageIDStart, timestamps.count);
                }
            }
            synchronized (monitor) {
                if (die || !newAdapters.isEmpty() || !messagesToSend.isEmpty()) {
                    continue;
                }
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Util.impossible(e);
                }
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public final class Binder extends android.os.Binder {
        public void addAdapter(MessageListAdapter adapter) {
            newAdapters.add(adapter);
            synchronized (monitor) {
                monitor.notify();
            }
        }
        
        public String getPhotosDir() {
            return photosDir;
        }

        public int getFirstLoaded() {
            return messageIDStart;
        }

        public int getLoadedCount() {
            return timestamps.count;
        }

        // TODO Once we are doing proper multithreading, ensure that these are inlined as much as possible.
        public long getTimestamp(int messageID) {
            return timestamps.data[messageID - messageIDStart];
        }

        public boolean getAuthor(int messageID) {
            return authors.data[messageID - messageIDStart];
        }

        public int getType(int messageID) {
            return indexes.data[messageID - messageIDStart] >> TYPE_SHIFT;
        }

        public int getIndex(int messageID) {
            return indexes.data[messageID - messageIDStart] & INDEX_MASK;
        }

        public String getText(int messageID) {
            return utf8Decode(texts.get(getIndex(messageID)));
        }

        public int getLoadedPhotoCount(int messageID) {
            return photoCounts.data[getIndex(messageID)];
        }

        private void sendMessage(int type, int index) {
            timestamps.add(System.currentTimeMillis());
            authors.add(Message.AUTHOR_US);
            indexes.add(type << MessageService.TYPE_SHIFT | index & MessageService.INDEX_MASK);
            messagesToSend.add(true);
            synchronized (monitor) {
                monitor.notify();
            }
        }

        public void sendMessage(String message) {
            texts.add(utf8Encode(message));
            sendMessage(MessageService.TYPE_TEXT, texts.size() - 1);
        }

        // TODO should we even be doing this processing here?
        public void sendPhoto(String tempPhotoFile) {
            int messageId = messageIDStart + timestamps.count;
            ensurePhotosDirExists();
            File photoDestFile = new File(photosDir, messageId + ".jpg");
            if (!new File(tempPhotoFile).renameTo(photoDestFile)) {
                // XXX Failure point
                throw new RuntimeException("Failed to rename " + tempPhotoFile + " to " + photoDestFile);
            }
            photoCounts.add((short) 1);
            sendMessage(MessageService.TYPE_PHOTOS, photoCounts.count - 1);
        }

        // Returns -1 on success, or else the index of the photo that failed.
        // XXX This is seriously redundant with the previous method
        public int sendPhotos(Uri[] photoUris) {
            // XXX Gack! This is *wrong* for single photos!
            int messageId = messageIDStart + timestamps.count;
            ensurePhotosDirExists();
            File messageDir = null;
            if (photoUris.length > 1) {
                messageDir = new File(photosDir, Integer.toString(messageId));
                messageDir.mkdir();
                if (!messageDir.isDirectory()) {
                    // XXX Failure point
                    throw new RuntimeException("Could not create directory " + messageDir);
                }
            }
            byte[] buffer = new byte[64 * 1024];
            for (int i = 0; i < photoUris.length; i++) {
                try {
                    String photoFile;
                    if (photoUris.length > 1) {
                        photoFile = messageDir + "/" + i + ".jpg";
                    } else {
                        photoFile = photosDir + "/" + messageId + ".jpg";
                    }
                    InputStream stream = App.openInputStream(photoUris[i]);
                    OutputStream out = new FileOutputStream(photoFile);
                    while (true) {
                        int read = stream.read(buffer);
                        if (read == -1) {
                            break;
                        }
                        out.write(buffer, 0, read);
                    }
                    out.close();
                } catch (IOException e) {
                    // XXX Failure point
                    return i;
                }
            }
            // XXX Enforce the limit!
            photoCounts.add((short) photoUris.length);
            sendMessage(MessageService.TYPE_PHOTOS, photoCounts.count - 1);
            return -1;
        }

        private void ensurePhotosDirExists() {
            File photosDirFile = new File(photosDir);
            photosDirFile.mkdir();
            if (!photosDirFile.isDirectory()) {
                // XXX Failure point
                throw new RuntimeException("Directory " + photosDir + " doesn't exist?");
            }
        }
    }

    @Override
    public void onDestroy() {
        die = true;
        synchronized (monitor) {
            monitor.notify();
        }
        try {
            mainThread.join();
        } catch (InterruptedException e) {
            Util.impossible(e);
        }
    }
}
