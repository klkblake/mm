package com.klkblake.mm;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Created by kyle on 1/10/15.
 */
public class MessageListAdapter extends BaseAdapter implements AbsListView.RecyclerListener {
    public static final int TYPE_SHIFT = 29;
    public static final int INDEX_MASK = (1 << TYPE_SHIFT) - 1;
    public static final int MAX_PREVIEW_PHOTOS = 9;
    public static final boolean AUTHOR_US = true;
    public static final boolean AUTHOR_THEM = false;
    public static final int TYPE_PENDING = 0;
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_PHOTOS_UNLOADED = 2;
    public static final int TYPE_PHOTOS_LOADED = 3; // Must be last

    private int messageIDStart;
    private LongArray timestamps = new LongArray();
    private BooleanArray authors = new BooleanArray();
    private IntArray indexes = new IntArray();
    // Type 0 - Text
    private ArrayList<String> texts = new ArrayList<>(); // TODO manual string alloc
    // Type 1 - Loaded photos
    private ArrayList<Bitmap[]> photos = new ArrayList<>(); // XXX This is Not Ok
    private ShortArray loadedPhotoCounts = new ShortArray();
    // Type 2 - Unloaded photos
    private ShortArray unloadedPhotoCounts = new ShortArray();
    // Type 3 - Pending content
    // TODO implement pending data
    private final File photosDir;
    //private ArrayList<Message> messages = new ArrayList<>();

    {
        add(0,   AUTHOR_THEM, "How are you?");
        add(3,   AUTHOR_US,   "I am good, thank you! I slept very well");
        add(42,  AUTHOR_US,   "How about you?");
        add(128, AUTHOR_THEM, "I slept reasonably well too.");
    }

    public MessageListAdapter(File cacheDir) {
        photosDir = new File(cacheDir, "photos");
    }

    @Override
    public int getCount() {
        return timestamps.count;
    }

    @Override
    public Object getItem(int position) {
        throw new RuntimeException("What the hell do we even do here?"); // XXX figure this out
    }

    @Override
    public long getItemId(int position) {
        return messageIDStart + position;
    }

    @Override
    public int getItemViewType(int position) {
        int indexEntry = indexes.data[position];
        int type = indexEntry >> TYPE_SHIFT;
        if (type == TYPE_PHOTOS_LOADED) {
            int index = indexEntry & INDEX_MASK;
            int photoCount = loadedPhotoCounts.data[index];
            if (photoCount > MAX_PREVIEW_PHOTOS) {
                photoCount = MAX_PREVIEW_PHOTOS + 1;
            }
            type += photoCount - 1;
        }
        return type;
    }

    @Override
    public int getViewTypeCount() {
        return TYPE_PHOTOS_LOADED + MAX_PREVIEW_PHOTOS + 1;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        boolean author = authors.data[position];
        int indexEntry = indexes.data[position];
        int type = indexEntry >> TYPE_SHIFT;
        int index = indexEntry & INDEX_MASK;
        Context context = parent.getContext();
        switch (type) {
            case TYPE_TEXT: {
                TextView view = (TextView) convertView;
                if (view == null) {
                    view = new TextView(context);
                }
                int color;
                // TODO actually look up author colors
                if (author == AUTHOR_US) {
                    color = 0xffffaaaa;
                } else {
                    color = 0xffaaffaa;
                }
                ColorStateList textColor;
                // "Dark" and "Light" in these color names refer to the themes they are part of
                if (Util.perceivedBrightness(color) < 0.5f) {
                    textColor = App.resources.getColorStateList(R.color.abc_primary_text_material_dark);
                } else {
                    textColor = App.resources.getColorStateList(R.color.abc_primary_text_material_light);
                }
                view.setBackgroundColor(color);
                view.setTextColor(textColor);
                view.setText(texts.get(index));
                return view;
            }
            case TYPE_PHOTOS_LOADED: {
                // TODO share from contextual menu on long press
                Bitmap[] previewPhotos = photos.get(index);
                int previewCount = previewPhotos.length;
                final int count = loadedPhotoCounts.data[index];
                if (count == 1) {
                    ImageView view = (ImageView) convertView;
                    if (view == null) {
                        view = new ImageView(context);
                    }
                    view.setImageBitmap(previewPhotos[0]);
                    view.setAdjustViewBounds(true);
                    final Uri uri = App.getUriForFile(new File(photosDir, (messageIDStart + position) + ".jpg"));
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
                boolean overflow = count > MAX_PREVIEW_PHOTOS;
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
                            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            if (photoIndex++ == MAX_PREVIEW_PHOTOS - 1 && overflow) {
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
                int photoIndex = 0;
                for (int rowIndex = 0; rowIndex < view.getChildCount(); rowIndex++) {
                    TableRow row = (TableRow) view.getChildAt(rowIndex);
                    for (int columnIndex = 0; columnIndex < row.getChildCount(); columnIndex++) {
                        ImageView entry;
                        if (photoIndex == MAX_PREVIEW_PHOTOS - 1 && overflow) {
                            FrameLayout frame = (FrameLayout) row.getChildAt(columnIndex);
                            ((TextView) frame.getChildAt(1)).setText("+ " + (count - previewCount) + " more");
                            entry = (ImageView) frame.getChildAt(0);
                        } else {
                            entry = (ImageView) row.getChildAt(columnIndex);
                        }
                        entry.setImageBitmap(previewPhotos[photoIndex++]);
                    }
                }
                final File messageDir = new File(photosDir, Integer.toString(messageIDStart + position));
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
        throw new AssertionError("Invalid/unimplemented message type: " + type);
    }

    @Override
    public void onMovedToScrapHeap(View view) {
        if (view instanceof ImageView) {
            ((ImageView) view).setImageBitmap(null);
        } else if (view instanceof TableLayout) {
            TableLayout table = (TableLayout) view;
            for (int rowIndex = 0; rowIndex < table.getChildCount(); rowIndex++) {
                TableRow row = (TableRow) table.getChildAt(rowIndex);
                for (int columnIndex = 0; columnIndex < row.getChildCount(); columnIndex++) {
                    View entry = row.getChildAt(columnIndex);
                    if (entry instanceof ImageView) {
                        ((ImageView) entry).setImageBitmap(null);
                    } else if (entry instanceof FrameLayout) {
                        ((ImageView) ((FrameLayout) entry).getChildAt(0)).setImageBitmap(null);
                    }
                }
            }
        }
    }

    private void add(long timestamp, boolean author, int type, int index) {
        timestamps.add(timestamp);
        authors.add(author);
        indexes.add(type << TYPE_SHIFT | index & INDEX_MASK);
        notifyDataSetChanged();
    }

    public void add(long timestamp, boolean author, String message) {
        texts.add(message);
        add(timestamp, author, TYPE_TEXT, texts.size() - 1);
    }

    // TODO should we even be doing this processing here? No, no we should not
    public void add(long timestamp, boolean author, Bitmap photo, File photoFile) {
        int messageId = messageIDStart + timestamps.count;
        photosDir.mkdir();
        if (!photosDir.isDirectory()) {
            // XXX Failure point
            throw new RuntimeException("Directory " + photosDir + " doesn't exist?");
        }
        File photoDestFile = new File(photosDir, messageId + ".jpg");
        if (!photoFile.renameTo(photoDestFile)) {
            // XXX Failure point
            throw new RuntimeException("Failed to rename " + photoFile + " to " + photoDestFile);
        }
        photos.add(new Bitmap[]{photo});
        loadedPhotoCounts.add((short) 1);
        add(timestamp, author, TYPE_PHOTOS_LOADED, photos.size() - 1);
    }

    // Returns -1 on success, or else the index of the photo that failed.
    // XXX This is seriously redundant with the previous method
    public int add(long timestamp, boolean author, Bitmap[] photos, Uri[] photoUris) {
        // XXX Gack! This is *wrong* for single photos!
        int messageId = messageIDStart + timestamps.count;
        photosDir.mkdir();
        if (!photosDir.isDirectory()) {
            // XXX Failure point
            throw new RuntimeException("Could not create directory " + photosDir);
        }
        File messageDir = null;
        if (photos.length > 1) {
            messageDir = new File(photosDir, Integer.toString(messageId));
            messageDir.mkdir();
            if (!messageDir.isDirectory()) {
                // XXX Failure point
                throw new RuntimeException("Could not create directory " + messageDir);
            }
        }
        Uri[] newPhotoUris = new Uri[photoUris.length];
        byte[] buffer = new byte[64*1024];
        for (int i = 0; i < photoUris.length; i++) {
            try {
                File photoFile;
                if (photos.length > 1) {
                    photoFile = new File(messageDir, i + ".jpg");
                } else {
                    photoFile = new File(photosDir, messageId + ".jpg");
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
                newPhotoUris[i] = App.getUriForFile(photoFile);
            } catch (IOException e) {
                // XXX Failure point
                return i;
            }
        }
        this.photos.add(photos);
        // XXX Enforce the limit!
        loadedPhotoCounts.add((short) photoUris.length);
        add(timestamp, author, TYPE_PHOTOS_LOADED, this.photos.size() - 1);
        return -1;
    }
}
