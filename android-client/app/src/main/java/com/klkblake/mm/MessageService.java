package com.klkblake.mm;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.klkblake.mm.common.Message;
import com.klkblake.mm.common.Session;
import com.klkblake.mm.common.SessionListener;
import com.klkblake.mm.common.Storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;


public final class MessageService extends Service implements SessionListener {
    private static final String TAG = "MessageService";

    private Storage storage;
    private Session session;
    private Binder binder;
    private volatile ChatActivity activity;

    // These are package-access so we can cheaply access them from the inner class.
    String photosDir;

    @Override
    public void onCreate() {
        // TODO clean up temp dirs?
        photosDir = getCacheDir() + "/photos";
        storage = new Storage(photosDir);
        try {
            session = new Session(BuildConfig.DEBUG ? "localhost" : "klkblake.com", storage, this);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // TODO proper death notification! Unexpected exception tracking!
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
        ChatActivity activity_ = activity;
        if (activity_ != null) {
            activity_.updateMessages(storage.getMessageCount());
        }
    }

    @Override
    public void receivedMessage(long id, long timestamp, boolean author, int numPhotos) {
        ChatActivity activity_ = activity;
        if (activity_ != null) {
            activity_.updateMessages(storage.getMessageCount());
        }
    }

    @Override
    public void receivedPart(long messageID, int partID) {
        ChatActivity activity_ = activity;
        if (activity_ != null) {
            activity_.updateMessages(storage.getMessageCount());
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
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle("Could not connect to server");
        builder.setContentText("A network error occurred");
        builder.setVisibility(Notification.VISIBILITY_SECRET);
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(cause.getMessage()));
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(0, builder.build());
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
        public void setActivity(ChatActivity activity) {
            MessageService.this.activity = activity;
            if (activity != null) {
                activity.updateMessages(storage.getMessageCount());
            }
        }

        public String getPhotosDir() {
            return photosDir;
        }

        public void sendMessage(AndroidUser contact, String message) {
            session.sendMessage(contact, message);
        }

        public void sendPhoto(AndroidUser contact, String tempPhotoFile) {
            session.sendPhotos(contact, new File(tempPhotoFile));
            // TODO delete temp files in case of error?
        }

        public void sendPhotos(AndroidUser contact, Uri[] photoUris) {
            // XXX This is in the UI thread! You do not touch the FS in the UI thread
            File[] photos = new File[photoUris.length];
            for (int i = 0; i < photos.length; i++) {
                try {
                    ParcelFileDescriptor fd = App.contentResolver.openFileDescriptor(photoUris[i], "r");
                    if (fd == null) {
                        throw new IOException("null fd for photo " + i);
                    }
                    FileChannel src = new FileInputStream(fd.getFileDescriptor()).getChannel();
                    // TODO clean these up on error
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
            session.sendPhotos(contact, photos);
        }

        public Message getMessage(int id) {
            return storage.getMessage(id);
        }
    }

    @Override
    public void onDestroy() {
        session.close();
    }
}
