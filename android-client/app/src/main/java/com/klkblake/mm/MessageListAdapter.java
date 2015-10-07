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
import android.widget.ListView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import static com.klkblake.mm.MessageService.TYPE_PHOTOS_LOADED;
import static com.klkblake.mm.MessageService.TYPE_TEXT;

/**
 * Created by kyle on 1/10/15.
 */
public class MessageListAdapter extends BaseAdapter implements AbsListView.RecyclerListener {
    public static final int MAX_PREVIEW_PHOTOS = 9;

    private ListView listView;
    private MessageService.Binder service = null;
    private String photosDir;
    private int messageIDStart = 0;
    private int messageCount = 0;

    public MessageListAdapter(ListView listView) {
        this.listView = listView;
    }

    public void onServiceConnected(MessageService.Binder service) {
        this.service = service;
        service.addAdapter(this);
        photosDir = service.getPhotosDir();
    }

    public void updateLoadedRange(final int newMessageIDStart, final int newMessageCount) {
        listView.post(new Runnable() {
            @Override
            public void run() {
                int position = listView.getFirstVisiblePosition();
                View view = listView.getChildAt(0);
                int top = 0;
                if (view != null) {
                    top = view.getTop();
                    position += messageIDStart - newMessageIDStart;
                }
                messageIDStart = newMessageIDStart;
                messageCount = newMessageCount;
                notifyDataSetChanged();
                if (view != null) {
                    listView.setSelectionFromTop(position, top);
                }
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
        int messageID = messageIDStart + position;
        int type = service.getType(messageID);
        if (type == TYPE_PHOTOS_LOADED) {
            int photoCount = service.getLoadedPhotoCount(messageID);
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
        int messageID = messageIDStart + position;
        boolean author = service.getAuthor(messageID);
        int type = service.getType(messageID);
        //XXX int index = service.getIndex(messageID);
        Context context = parent.getContext();
        switch (type) {
            case TYPE_TEXT: {
                TextView view = (TextView) convertView;
                if (view == null) {
                    view = new TextView(context);
                }
                int color;
                // TODO actually look up author colors
                if (author == Message.AUTHOR_US) {
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
                view.setText(service.getText(messageID));
                return view;
            }
            case TYPE_PHOTOS_LOADED: {
                // TODO share from contextual menu on long press
                Bitmap[] previewPhotos = service.getPhotos(messageID);
                int previewCount = previewPhotos.length;
                final int count = service.getLoadedPhotoCount(messageID);
                if (count == 1) {
                    ImageView view = (ImageView) convertView;
                    if (view == null) {
                        view = new ImageView(context);
                    }
                    view.setImageBitmap(previewPhotos[0]);
                    view.setAdjustViewBounds(true);
                    final Uri uri = App.getUriForPath(photosDir + "/" + (messageIDStart + position) + ".jpg");
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
                final String messageDir = photosDir + "/" + (messageIDStart + position);
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
}
