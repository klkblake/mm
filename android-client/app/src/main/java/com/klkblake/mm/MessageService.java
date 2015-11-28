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
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

// XXX This class in general kinda sucks. It basically exists to (a) hook into Android's service
// framework, and (b) multiplex the stuff we have received from the Session. Except that would then
// lead to multiple activities running on the same session and responding to the callbacks, which is
// Bad, because we may double-respond. We really need to think about what we are doing w.r.t
// multiple activities and multiple conversations.
public final class MessageService extends Service implements Runnable, SessionListener {
    private static final String TAG = "MessageService";

    private Thread mainThread;
    private boolean die = false;
    private Storage storage;
    private Session session;
    private Binder binder;

    // These are package-access so we can cheaply access them from the inner class.
    String photosDir;

    final Object monitor = new Object();
    ConcurrentLinkedQueue<ChatActivity> newActivities = new ConcurrentLinkedQueue<>();
    ArrayList<ChatActivity> activities = new ArrayList<>();
    private int messageCount = 0;
    // Increment to trigger new message update -- may not always indicate a change in messageCount
    private int messageVersion = 0;

    @Override
    public void onCreate() {
        // TODO clean up temp dirs?
        photosDir = getCacheDir() + "/photos";
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
        int currentVersion = 0;
        while (!die) {
            for (ChatActivity activity; (activity = newActivities.poll()) != null; ) {
                if (activities.contains(activity)) {
                    continue;
                }
                activity.updateMessages(messageCount);
                activities.add(activity);
            }
            while (currentVersion != messageVersion) {
                currentVersion = messageVersion;
                // XXX How should we be handling activities in the background? They should definitely be detached if they are about to be destroyed.
                // TODO Ok, new plan: A main activity to choose who to talk to with launchMode=singleTask, set up to only launch the message window task once. This vastly simplifies everything, and is easier to use for the user as well.
                for (ChatActivity activity : activities) {
                    activity.updateMessages(messageCount);
                }
            }
            synchronized (monitor) {
                if (die || !newActivities.isEmpty() || currentVersion != messageVersion) {
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
        messageVersion++;
        synchronized (monitor) {
            monitor.notify();
        }
    }

    @Override
    public void receivedMessage(long id, long timestamp, boolean author, int numPhotos) {
        messageCount = storage.getMessageCount();
        messageVersion++;
        synchronized (monitor) {
            monitor.notify();
        }
    }

    @Override
    public void receivedPart(long messageID, int partID) {
        messageVersion++;
        synchronized (monitor) {
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
        public void addActivity(ChatActivity activity) {
            newActivities.add(activity);
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
