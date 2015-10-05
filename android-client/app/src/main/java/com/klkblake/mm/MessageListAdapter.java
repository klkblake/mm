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

import static com.klkblake.mm.Message.*;

/**
 * Created by kyle on 1/10/15.
 */
public class MessageListAdapter extends BaseAdapter implements AbsListView.RecyclerListener {
    public static final int MAX_ALBUM_PREVIEW_PHOTOS = 9;
    private final File photosDir;
    private ArrayList<Message> messages = new ArrayList<>();

    {
        messages.add(new Message(0, AUTHOR_THEM, "How are you?"));
        messages.add(new Message(1, AUTHOR_US, "I am good, thank you! I slept very well"));
        messages.add(new Message(2, AUTHOR_US, "How about you?"));
        messages.add(new Message(3, AUTHOR_THEM, "I slept reasonably well too."));
    }

    public MessageListAdapter(File cacheDir) {
        photosDir = new File(cacheDir, "photos");
    }

    @Override
    public int getCount() {
        return messages.size();
    }

    @Override
    public Object getItem(int position) {
        return messages.get(position);
    }

    @Override
    public long getItemId(int position) {
        return messages.get(position).messageId;
    }

    @Override
    public int getItemViewType(int position) {
        Message message = messages.get(position);
        if (message.type == MessageType.ALBUM) {
            // We have a separate type for an overflowing grid.
            int nudge = message.photoUris.length > MAX_ALBUM_PREVIEW_PHOTOS ? 0 : 1;
            return MessageType.values().length + message.photos.length - nudge;
        }
        return messages.get(position).type.ordinal();
    }

    @Override
    public int getViewTypeCount() {
        return MessageType.values().length + MAX_ALBUM_PREVIEW_PHOTOS + 1;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Message message = messages.get(position);
        Context context = parent.getContext();
        switch (message.type) {
            case TEXT: {
                TextView view = (TextView) convertView;
                if (view == null) {
                    view = new TextView(context);
                }
                int color = 0;
                if (message.author == AUTHOR_US) {
                    color = 0xffffaaaa;
                } else if (message.author == AUTHOR_THEM) {
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
                view.setText(message.text);
                return view;
            }
            case PHOTO: {
                ImageView view = (ImageView) convertView;
                if (view == null) {
                    view = new ImageView(context);
                }
                view.setImageBitmap(message.photos[0]);
                view.setAdjustViewBounds(true);
                view.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.setDataAndType(message.photoUris[0], "image/jpeg");
                        App.tryStartActivity(intent);
                    }
                });
                return view;
            }
            case ALBUM: {
                int count = message.photos.length;
                boolean overflow = message.photoUris.length > MAX_ALBUM_PREVIEW_PHOTOS;
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
                    if (count == 1) {
                        columns = 1;
                    } else if (count <= 4) {
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
                            if (photoIndex++ == MAX_ALBUM_PREVIEW_PHOTOS - 1 && overflow) {
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
                            if (photoIndex == count) {
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
                        if (photoIndex == MAX_ALBUM_PREVIEW_PHOTOS - 1 && overflow) {
                            FrameLayout frame = (FrameLayout) row.getChildAt(columnIndex);
                            ((TextView) frame.getChildAt(1)).setText("+ " + Integer.toString(message.photoUris.length - count) + " more");
                            entry = (ImageView) frame.getChildAt(0);
                        } else {
                            entry = (ImageView) row.getChildAt(columnIndex);
                        }
                        entry.setImageBitmap(message.photos[photoIndex++]);
                    }
                }
                view.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // FIXME do properly
                        Intent intent = new Intent(App.context, AlbumActivity.class);
                        intent.putExtra(AlbumActivity.EXTRA_PHOTO_URIS, new ArrayList<>(Arrays.asList(message.photoUris)));
                        App.startActivity(intent);
                    }
                });
                return view;
            }
        }
        throw new AssertionError("Invalid message type: " + message.type);
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

    private void add(Message message) {
        messages.add(message);
        notifyDataSetChanged();
    }

    public void add(int author, String message) {
        add(new Message(messages.size(), author, message));
    }

    // TODO should we even be doing this processing here?
    public void add(int author, Bitmap photo, File photoFile) {
        int messageId = messages.size();
        photosDir.mkdir();
        if (!photosDir.isDirectory()) {
            // XXX Failure point
            throw new RuntimeException("Directory " + photosDir + " doesn't exist?");
        }
        File photoDestFile = new File(photosDir, Integer.toString(messageId) + ".jpg");
        if (!photoFile.renameTo(photoDestFile)) {
            // XXX Failure point
            throw new RuntimeException("Failed to rename " + photoFile + " to " + photoDestFile);
        }
        add(new Message(messageId, author, photo, App.getUriForFile(photoDestFile)));
    }

    // Returns -1 on success, or else the index of the photo that failed.
    public int add(int author, Bitmap[] photos, Uri[] photoUris) {
        int messageId = messages.size();
        photosDir.mkdir();
        if (!photosDir.isDirectory()) {
            // XXX Failure point
            throw new RuntimeException("Could not create directory " + photosDir);
        }
        File messageDir = new File(photosDir, Integer.toString(messageId));
        messageDir.mkdir();
        if (!messageDir.isDirectory()) {
            // XXX Failure point
            throw new RuntimeException("Could not create directory " + messageDir);
        }
        Uri[] newPhotoUris = new Uri[photoUris.length];
        byte[] buffer = new byte[64*1024];
        for (int i = 0; i < photoUris.length; i++) {
            try {
                File photoFile = new File(messageDir, Integer.toString(i) + ".jpg");
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
        add(new Message(messageId, author, photos, newPhotoUris));
        return -1;
    }
}
