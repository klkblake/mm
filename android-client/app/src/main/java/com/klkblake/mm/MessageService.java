package com.klkblake.mm;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class MessageService extends Service implements Runnable, SessionListener {
    private static final String TAG = "MessageService";

    private Thread mainThread;
    private boolean die;
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
        session = new Session(photosDir, this);
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

    // TODO are these callbacks actually useful as is?
    @Override
    public void receivedMessage(long id, long timestamp, boolean author, String message) {
        messageCount = session.getMessageCount();
        synchronized (monitor) {
            updateRequired = true;
            monitor.notify();
        }
    }

    @Override
    public void receivedMessage(long id, long timestamp, boolean author, int numPhotos) {
        messageCount = session.getMessageCount();
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
    public void networkFailed(Throwable cause) {
        // TODO report somehow
        Log.i(TAG, "Network issue", cause);
    }

    @Override
    public void protocolViolation(String message) {
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
                    InputStream in = App.openInputStream(photoUris[i]);
                    photos[i] = File.createTempFile("photo", ".jpg", App.context.getFilesDir());
                    ReadableByteChannel src = Channels.newChannel(in);
                    FileChannel dst = new FileOutputStream(photos[i]).getChannel();
                    ByteBuffer buf = ByteBuffer.allocateDirect(65536);
                    while (src.read(buf) != -1) {
                        buf.flip();
                        while (buf.hasRemaining()) {
                            dst.write(buf);
                        }
                        buf.clear();
                    }
                    src.close();
                    dst.close();
                } catch (IOException e) {
                    // TODO notify/toast
                    // XXX ehhh, this loses info. Do not like.
                }
            }
            session.sendPhotos(photos);
        }

        public Message getMessage(int id) {
            return session.getMessage(id);
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
