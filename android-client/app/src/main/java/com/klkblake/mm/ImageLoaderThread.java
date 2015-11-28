package com.klkblake.mm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.util.LruCache;
import android.view.WindowManager;
import android.widget.ListView;

import java.util.ArrayDeque;

/**
 * Created by kyle on 27/11/15.
 */
public class ImageLoaderThread extends Thread {
    private final String photosDir;
    private final ListView view;
    private final ChatActivity.MessageListAdapter adapter;

    private boolean die = false;
    private final ArrayDeque<ImageLoadRequest> loadImageRequests = new ArrayDeque<>();
    // TODO do we want to decode ahead of a scroll?
    private LruCache<ImageLoadRequest, Bitmap> imageCache;

    // TODO use this from PhotoActivity as well.
    public ImageLoaderThread(String photosDir, ListView view, ChatActivity.MessageListAdapter adapter) {
        super("Image Loader");
        this.photosDir = photosDir;
        this.view = view;
        this.adapter = adapter;

        WindowManager wm = (WindowManager) App.context.getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getSize(size);
        int numBoxes;
        if (size.x < size.y) {
            // Portrait
            numBoxes = size.y / size.x + 2;
        } else {
            // Landscape or square
            numBoxes = 3;
        }
        int pixels = numBoxes * size.x * size.x;
        // TODO make excess size configurable
        imageCache = new LruCache<ImageLoaderThread.ImageLoadRequest, Bitmap>(pixels * 4 + 8 * 1024 * 1024) {
            @Override
            protected int sizeOf(ImageLoaderThread.ImageLoadRequest key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    @Override
    public void run() {
        while (!die) {
            boolean loadedAnImage = false;
            while (!die) {
                final ImageLoadRequest request;
                synchronized (loadImageRequests) {
                    request = loadImageRequests.peek();
                    if (request == null) {
                        break;
                    }
                }
                String path;
                if (request.isSingle) {
                    path = photosDir + "/" + request.message + ".jpg";
                } else {
                    path = photosDir + "/" + request.message + "/" + request.part + ".jpg";
                }
                final Bitmap bitmap = App.decodeSampledBitmap(path, request.reqWidth, request.reqHeight);
                // TODO handle decode fail
                // TODO If file doesn't exist yet, wait for notification from Session.
                imageCache.put(request, bitmap);
                loadImageRequests.remove();
                loadedAnImage = true;
            }
            if (loadedAnImage) {
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
            synchronized (loadImageRequests) {
                if (die || !loadImageRequests.isEmpty()) {
                    continue;
                }
                try {
                    loadImageRequests.wait();
                } catch (InterruptedException e) {
                    Util.impossible(e);
                }
            }
        }
    }

    public Bitmap request(int messageID, int part, boolean isSingle, int size) {
        ImageLoadRequest request = new ImageLoadRequest(messageID, part, isSingle, size, size);
        Bitmap bitmap = imageCache.get(request);
        if (bitmap == null) {
            synchronized (loadImageRequests) {
                if (!loadImageRequests.contains(request)) {
                    loadImageRequests.add(request);
                    loadImageRequests.notify();
                }
            }
            return null;
        } else {
            return bitmap;
        }
    }

    public void stopSafely() {
        die = true;
        synchronized (loadImageRequests) {
            loadImageRequests.notify();
        }
        try {
            join();
        } catch (InterruptedException e) {
            Util.impossible(e);
        }
    }

    static class ImageLoadRequest {
        public final int message;
        public final int part;
        public final boolean isSingle;
        public final int reqWidth;
        public final int reqHeight;

        public ImageLoadRequest(int message, int part, boolean isSingle, int reqWidth, int reqHeight) {
            this.message = message;
            this.part = part;
            this.isSingle = isSingle;
            this.reqWidth = reqWidth;
            this.reqHeight = reqHeight;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ImageLoadRequest that = (ImageLoadRequest) o;

            return (message == that.message &&
                    part == that.part &&
                    isSingle == that.isSingle &&
                    reqWidth == that.reqWidth &&
                    reqHeight == that.reqHeight);

        }

        @Override
        public int hashCode() {
            int result = message;
            result = 31 * result + part;
            result = 31 * result + (isSingle ? 1 : 0);
            result = 31 * result + reqWidth;
            result = 31 * result + reqHeight;
            return result;
        }
    }
}
