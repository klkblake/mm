package com.klkblake.mm;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayDeque;

import static com.klkblake.mm.Message.TYPE_PHOTOS;
import static com.klkblake.mm.Message.TYPE_TEXT;
import static com.klkblake.mm.Util.max;
import static com.klkblake.mm.Util.min;

/**
 * Created by kyle on 1/10/15.
 */
public class MessageListAdapter extends BaseAdapter implements AbsListView.RecyclerListener, Runnable {
    public static final int MAX_PREVIEW_PHOTOS = 9;
    private final Thread loaderThread;

    private ListView listView;
    private MessageService.Binder service = null;
    private String photosDir;
    private int messageIDStart = 0;
    private int messageCount = 0;
    private int textColorLight, textColorDark;

    private final ArrayDeque<ImageLoadRequest> loadImageRequests = new ArrayDeque<>();
    // TODO do we want to decode ahead of a scroll?
    private LruCache<ImageLoadRequest, Bitmap> imageCache;
    private final Runnable notifyDataSetChanged = new Runnable() {
        @Override
        public void run() {
            notifyDataSetChanged();
        }
    };
    private boolean die = false;

    public MessageListAdapter(ListView listView) {
        this.listView = listView;
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
        imageCache = new LruCache<ImageLoadRequest, Bitmap>(pixels * 4 + 8 * 1024 * 1024) {
            @Override
            protected int sizeOf(ImageLoadRequest key, Bitmap value) {
                return value.getByteCount();
            }
        };
        TypedArray colors = App.context.getTheme().obtainStyledAttributes(new int[]{
                android.R.attr.textColorPrimary,
                android.R.attr.textColorPrimaryInverse
        });
        textColorLight = colors.getColor(0, 0xffffffff);
        textColorDark = colors.getColor(1, 0xff000000);
        colors.recycle();
        loaderThread = new Thread(this, "Image loader");
        loaderThread.start();
    }

    public void onServiceConnected(MessageService.Binder service) {
        this.service = service;
        service.addAdapter(this);
        photosDir = service.getPhotosDir();
    }

    public void updateMessages(final int newMessageCount) {
        listView.post(new Runnable() {
            @Override
            public void run() {
                messageCount = newMessageCount;
                notifyDataSetChanged();
            }
        });
    }

    @Override
    public int getCount() {
        return messageCount;
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    @Override
    public int getItemViewType(int position) {
        Message message = service.getMessage(messageIDStart + position);
        int type = message.type;
        if (type == TYPE_PHOTOS) {
            int photoCount = message.photoCount;
            if (photoCount > MAX_PREVIEW_PHOTOS) {
                photoCount = MAX_PREVIEW_PHOTOS + 1;
            }
            type += photoCount - 1;
        }
        return type;
    }

    @Override
    public int getViewTypeCount() {
        return TYPE_PHOTOS + MAX_PREVIEW_PHOTOS + 1;
    }

    @Override
    public void run() {
        while (!die) {
            boolean loadedAnImage = false;
            while (true) {
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
                listView.post(notifyDataSetChanged);
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

    private void loadImageBitmap(int messageID, int part, ImageView view, boolean isSingle, int size) {
        ImageLoadRequest request = new ImageLoadRequest(messageID, part, isSingle, size, size);
        Bitmap bitmap = imageCache.get(request);
        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            return;
        }
        synchronized (loadImageRequests) {
            if (!loadImageRequests.contains(request)) {
                loadImageRequests.add(request);
                loadImageRequests.notify();
            }
        }
        view.setImageDrawable(null);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        int messageID = messageIDStart + position;
        Message message = service.getMessage(messageIDStart + position);
        Context context = parent.getContext();
        switch (message.type) {
            case TYPE_TEXT: {
                TextView view = (TextView) convertView;
                if (view == null) {
                    view = new TextView(context);
                }
                int color;
                // TODO actually look up author colors
                if (message.author == Message.AUTHOR_US) {
                    color = 0xffffaaaa;
                } else {
                    color = 0xffaaffaa;
                }
                view.setBackgroundColor(color);
                if (Util.perceivedBrightness(color) < 0.5f) {
                    view.setTextColor(textColorLight);
                } else {
                    view.setTextColor(textColorDark);
                }
                view.setText(message.text);
                return view;
            }
            case TYPE_PHOTOS: {
                // TODO share from contextual menu on long press
                final int count = message.photoCount;
                int previewCount = min(count, MAX_PREVIEW_PHOTOS);
                if (count == 1) {
                    ImageView view = (ImageView) convertView;
                    if (view == null) {
                        view = new SquareImageView(context);
                        view.setMinimumWidth(listView.getWidth());
                    }
                    loadImageBitmap(messageID, 0, view, true, listView.getWidth());
                    final Uri uri = App.getUriForPath(photosDir + "/" + messageID + ".jpg");
                    view.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            intent.setDataAndType(uri, "image/jpeg");
                            App.tryStartActivity(intent);
                        }
                    });
                    return view;
                }
                int overflow = max(count - MAX_PREVIEW_PHOTOS, 0);
                TableLayout view = (TableLayout) convertView;
                if (view == null) {
                    int rows, columns;
                    if (count <= 2) {
                        rows = 1;
                    } else if (count <= 6) {
                        rows = 2;
                    } else {
                        rows = 3;
                    }
                    if (count <= 4) {
                        columns = 2;
                    } else {
                        columns = 3;
                    }
                    int photoIndex = 0;
                    // FIXME latency
                    // TODO figure out the optimal order of operations to save time
                    view = new TableLayout(context);
                    view.setStretchAllColumns(true);
                    for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                        TableRow row = new TableRow(context);
                        for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                            View entry;
                            ImageView image = new SquareImageView(context);
                            image.setMinimumWidth(listView.getWidth() / columns);
                            if (photoIndex++ == MAX_PREVIEW_PHOTOS - 1 && overflow > 0) {
                                FrameLayout frame = new FrameLayout(context);
                                frame.addView(image);
                                TextView text = new TextView(context);
                                text.setBackgroundColor(0xc0202020);
                                text.setTextColor(0xffffffff);
                                text.setGravity(Gravity.CENTER);
                                frame.addView(text);
                                entry = frame;
                            } else {
                                entry = image;
                            }
                            entry.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT));
                            row.addView(entry);
                            if (photoIndex == previewCount) {
                                break;
                            }
                        }
                        view.addView(row);
                    }
                }
                int columns = ((TableRow) view.getChildAt(0)).getChildCount();
                int entryWidth = listView.getWidth() / columns;
                int photoIndex = 0;
                for (int rowIndex = 0; rowIndex < view.getChildCount(); rowIndex++) {
                    TableRow row = (TableRow) view.getChildAt(rowIndex);
                    for (int columnIndex = 0; columnIndex < row.getChildCount(); columnIndex++) {
                        ImageView entry;
                        if (photoIndex == MAX_PREVIEW_PHOTOS - 1 && overflow > 0) {
                            String label = App.resources.getQuantityString(R.plurals.more_pictures,
                                    overflow, overflow);
                            FrameLayout frame = (FrameLayout) row.getChildAt(columnIndex);
                            ((TextView) frame.getChildAt(1)).setText(label);
                            entry = (ImageView) frame.getChildAt(0);
                        } else {
                            entry = (ImageView) row.getChildAt(columnIndex);
                        }
                        loadImageBitmap(messageID, photoIndex++, entry, false, entryWidth);
                    }
                }
                final String messageDir = photosDir + "/" + messageID;
                view.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(App.context, PhotosActivity.class);
                        intent.putExtra(PhotosActivity.EXTRA_PHOTO_DIR, messageDir);
                        intent.putExtra(PhotosActivity.EXTRA_PHOTO_COUNT, count);
                        App.startActivity(intent);
                    }
                });
                return view;
            }
        }
        throw new AssertionError("Invalid/unimplemented message type: " + message.type);
    }

    @Override
    public void onMovedToScrapHeap(View view) {
        if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            imageView.setImageBitmap(null);
        } else if (view instanceof TableLayout) {
            TableLayout table = (TableLayout) view;
            for (int rowIndex = 0; rowIndex < table.getChildCount(); rowIndex++) {
                TableRow row = (TableRow) table.getChildAt(rowIndex);
                for (int columnIndex = 0; columnIndex < row.getChildCount(); columnIndex++) {
                    View entry = row.getChildAt(columnIndex);
                    ImageView imageView;
                    if (entry instanceof ImageView) {
                        imageView = (ImageView) entry;
                    } else if (entry instanceof FrameLayout) {
                        imageView = (ImageView) ((FrameLayout) entry).getChildAt(0);
                    } else {
                        throw new AssertionError("Entry not an instance of an appropriate class?");
                    }
                    imageView.setImageBitmap(null);
                }
            }
        }
    }

    public void onDestroy() {
        die = true;
        synchronized (loadImageRequests) {
            loadImageRequests.notify();
        }
        try {
            loaderThread.join();
        } catch (InterruptedException e) {
            Util.impossible(e);
        }
    }

    class ImageLoadRequest {
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
