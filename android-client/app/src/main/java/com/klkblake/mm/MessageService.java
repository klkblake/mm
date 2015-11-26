package com.klkblake.mm;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

// TODO This class in general kinda sucks.
public final class MessageService extends Service implements Runnable, SessionListener {
    private static final String TAG = "MessageService";

    private Thread mainThread;
    private boolean die;
    private Storage storage;
    private Session session;
    private Binder binder;

    // These are package-access so we can cheaply access them from the inner class.
    String photosDir;

    final Object monitor = new Object();
    ConcurrentLinkedQueue<MessageListAdapter> newAdapters = new ConcurrentLinkedQueue<>();
    // Accessed by multiple threads
    ArrayList<MessageListAdapter> adapters = new ArrayList<>();
    private int messageCount = 0;
    private boolean updateRequired = false;

    @Override
    public void onCreate() {
        // TODO clean up temp dirs?
        photosDir = getCacheDir() + "/photos";
        die = false;
        mainThread = new Thread(this, "Main service thread");
        mainThread.start();
        storage = new Storage(photosDir);
        session = new Session(storage, this);
        // TODO proper death notification!
        if (session.isDead()) {
            //TODO notification!
            Log.e(TAG, "Session couldn't start");
        }

        binder = new Binder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void run() {
        while (!die) {
            for (MessageListAdapter adapter; (adapter = newAdapters.poll()) != null; ) {
                adapter.updateMessages(messageCount);
                adapters.add(adapter);
            }
            if (updateRequired) {
                synchronized (monitor) {
                    for (MessageListAdapter adapter : adapters) {
                        adapter.updateMessages(messageCount);
                    }
                    updateRequired = false;
                }
            }
            synchronized (monitor) {
                if (die || !newAdapters.isEmpty() || updateRequired) {
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

    @Override
    public void receivedOurColor(int color) {
        // TODO real implementation
    }

    @Override
    public void receivedOurName(String name) {
        // TODO real implementation
    }

    @Override
    public void receivedOurAvatarSha256(byte[] avatarSha256) {
        // TODO real implementation
    }

    @Override
    public void receivedPeerColor(int color) {
        // TODO real implementation
    }

    @Override
    public void receivedPeerName(String name) {
        // TODO real implementation
    }

    @Override
    public void receivedPeerAvatarSha256(byte[] avatarSha256) {
        // TODO real implementation
    }

    // TODO are these callbacks actually useful as is?
    @Override
    public void receivedMessage(long id, long timestamp, boolean author, String message) {
        messageCount = storage.getMessageCount();
        synchronized (monitor) {
            updateRequired = true;
            monitor.notify();
        }
    }

    @Override
    public void receivedMessage(long id, long timestamp, boolean author, int numPhotos) {
        messageCount = storage.getMessageCount();
        synchronized (monitor) {
            updateRequired = true;
            monitor.notify();
        }
    }

    @Override
    public void receivedPart(long messageID, int partID) {
        synchronized (monitor) {
            updateRequired = true;
            monitor.notify();
        }
    }

    @Override
    public void badVersion(int minVersion, int maxVersion) {
        // TODO real implementation
        Log.e(TAG, "Bad version! Range is " + minVersion + " to " + maxVersion);
    }

    @Override
    public void unknownUser() {
        // TODO real implementation
        Log.wtf(TAG, "Unknown user!");
    }

    @Override
    public void unknownPeer() {
        // TODO real implementation
        Log.e(TAG, "Unknown peer");
    }

    @Override
    public void authenticationFailed(boolean isControlChannel) {
        String channel = isControlChannel ? "control" : "data";
        // TODO better reporting
        Log.e(TAG, "Authentication failed! Probable MITM attack on " + channel + " channel");
    }

    @Override
    public void networkFailed(Throwable cause) {
        // TODO report somehow
        Log.i(TAG, "Network issue", cause);
    }

    @Override
    public void protocolViolation(String message, Throwable cause) {
        // TODO report somehow
        Log.e(TAG, message);
    }

    @Override
    public void filesystemFailed(Exception exception) {
        // TODO report somehow
        Log.e(TAG, "Filesystem issue", exception);
    }

    @Override
    public void assertionFired(Exception assertion) {
        // TODO report somehow
        Log.wtf(TAG, assertion);
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

        public void sendMessage(String message) {
            session.sendMessage(message);
        }

        public void sendPhoto(String tempPhotoFile) {
            session.sendPhotos(new File(tempPhotoFile));
            // TODO delete temp files in case of error?
        }

        public void sendPhotos(Uri[] photoUris) {
            // XXX This is in the UI thread! You do not touch the FS in the UI thread
            File[] photos = new File[photoUris.length];
            for (int i = 0; i < photos.length; i++) {
                try {
                    ParcelFileDescriptor fd = App.contentResolver.openFileDescriptor(photoUris[i], "r");
                    if (fd == null) {
                        throw new IOException("null fd for photo " + i);
                    }
                    FileChannel src = new FileInputStream(fd.getFileDescriptor()).getChannel();
                    photos[i] = File.createTempFile("photo", ".jpg", App.context.getFilesDir());
                    FileChannel dst = new FileOutputStream(photos[i]).getChannel();
                    long position = 0;
                    while (true) {
                        long written = dst.transferFrom(src, position, Integer.MAX_VALUE);
                        if (written == 0) {
                            break;
                        }
                        position += written;
                    }
                    src.close();
                    dst.close();
                    fd.close();
                } catch (IOException e) {
                    // TODO notify/toast
                    // XXX ehhh, this loses info. Do not like.
                    Log.e(TAG, "failed to copy file", e);
                }
            }
            session.sendPhotos(photos);
        }

        public Message getMessage(int id) {
            return storage.getMessage(id);
        }
    }

    @Override
    public void onDestroy() {
        session.close();
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
