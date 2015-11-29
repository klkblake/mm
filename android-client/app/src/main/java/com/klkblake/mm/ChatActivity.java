package com.klkblake.mm;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.support.design.widget.FloatingActionButton;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

import static com.klkblake.mm.App.toast;
import static com.klkblake.mm.Message.TYPE_PHOTOS;
import static com.klkblake.mm.Message.TYPE_TEXT;
import static com.klkblake.mm.Util.max;
import static com.klkblake.mm.Util.min;


public class ChatActivity extends AppActivity {
    public static final String EXTRA_CONTACT = "contact";
    private static final int REQUEST_TAKE_PHOTO = 1;
    private static final int REQUEST_SELECT_PHOTOS = 2;

    private MessageService.Binder service = null;
    private ServiceConnection serviceConnection;
    private ImageLoaderThread loader = null;
    private MessageListAdapter messages;
    private String photosDir;
    private String tempPhotoPath;

    private ListView messageList;
    private EditText composeText;
    private FloatingActionButton sendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        messageList = (ListView) findViewById(R.id.messageList);
        composeText = (EditText) findViewById(R.id.composeText);
        sendButton = (FloatingActionButton) findViewById(R.id.sendButton);

        messages = new MessageListAdapter();
        messageList.setAdapter(messages);
        composeText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sendButton.setEnabled(s.toString().trim().length() != 0);
            }
        });

        startService(new Intent(App.context, MessageService.class));
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service_) {
                service = (MessageService.Binder) service_;
                photosDir = service.getPhotosDir();
                service.setActivity(ChatActivity.this);
                loader = new ImageLoaderThread(photosDir, messageList, messages);
                loader.start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // We are completely useless if we can't access the service -- we can't even scroll the list
                // view! So we bail.
                // TODO better handling of this condition -- this may not even kill the activity as-is
                service = null;
                if (loader != null) {
                    loader.stopSafely();
                }
                throw new RuntimeException("MessageService was killed");
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(App.context, MessageService.class), serviceConnection, BIND_IMPORTANT | BIND_ABOVE_CLIENT);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void sendMessage(View view) {
        if (service == null) {
            notifyServiceNotConnected();
            return;
        }
        Editable textContent = composeText.getText();
        String text = textContent.toString().trim();
        if (text.length() == 0) {
            return;
        }
        service.sendMessage(text);
        textContent.clear();
    }

    public void takePhoto(View view) {
        if (service == null) {
            notifyServiceNotConnected();
            return;
        }
        try {
            tempPhotoPath = File.createTempFile("photo", ".jpg", getFilesDir()).getPath();
        } catch (IOException e) {
            toast("Can't create temp file to store photo");
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, App.getUriForPath(tempPhotoPath));
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_TAKE_PHOTO);
        }
    }

    public void selectPhotos(View view) {
        if (service == null) {
            notifyServiceNotConnected();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        // TODO we need to make sure we actually handle PNGs at least
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_SELECT_PHOTOS);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_TAKE_PHOTO && resultCode == RESULT_OK) {
            service.sendPhoto(tempPhotoPath);
        }
        if (requestCode == REQUEST_SELECT_PHOTOS && resultCode == RESULT_OK) {
            ClipData selected = data.getClipData();
            Uri[] photoUris;
            if (selected == null) {
                photoUris = new Uri[]{data.getData()};
            } else {
                int count = selected.getItemCount();
                photoUris = new Uri[count];
                for (int i = 0; i < count; i++) {
                    photoUris[i] = selected.getItemAt(i).getUri();
                }
            }
            service.sendPhotos(photoUris);
        }
    }

    private void notifyServiceNotConnected() {
        toast("The service is not yet available.");
    }

    public void updateMessages(final int newMessageCount) {
        messageList.post(new Runnable() {
            @Override
            public void run() {
                messages.messageCount = newMessageCount;
                messages.notifyDataSetChanged();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        service.setActivity(null);
        service = null;
        unbindService(serviceConnection);
        if (loader != null) {
            loader.stopSafely();
        }
    }

    public class MessageListAdapter extends ColoredListAdapter implements AbsListView.RecyclerListener {
        public static final int MAX_PREVIEW_PHOTOS = 9;

        // TODO consider how we want to handle this -- messageIDStart is never written ATM
        private int messageIDStart = 0;
        private int messageCount = 0;

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
        public View getView(int position, View convertView, ViewGroup parent) {
            final int messageID = messageIDStart + position;
            Message message = service.getMessage(messageIDStart + position);
            Context context = parent.getContext();
            switch (message.type) {
                case TYPE_TEXT: {
                    TextView view = (TextView) convertView;
                    if (view == null) {
                        view = new TextView(context);
                        initSelectableView(view, getDrawable(R.drawable.colored_selectable_item_background));
                    }
                    int color;
                    // TODO actually look up author colors
                    if (message.author == Message.AUTHOR_US) {
                        color = 0xffffaaaa;
                    } else {
                        color = 0xffaaffaa;
                    }
                    setSelectableBackgroundColor(view, color);
                    view.setText(message.text);
                    return view;
                }
                case TYPE_PHOTOS: {
                    // TODO share from contextual menu on long press
                    final int count = message.photoCount;
                    int previewCount = min(count, MAX_PREVIEW_PHOTOS);
                    int width = messageList.getWidth();
                    if (count == 1) {
                        ImageView view = (ImageView) convertView;
                        if (view == null) {
                            view = new SquareImageView(context);
                            view.setMinimumWidth(width);
                        }
                        view.setImageBitmap(loader.request(messageID, 0, true, width));
                        final Uri uri = App.getUriForPath(photosDir + "/" + messageID + ".jpg");
                        view.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                intent.setDataAndType(uri, "image/jpeg");
                                App.tryStartActivity(intent); // TODO should we be using try...() here?
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
                                image.setMinimumWidth(width / columns);
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
                    int entryWidth = width / columns;
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
                            int part = photoIndex++;
                            entry.setImageBitmap(loader.request(messageID, part, false, entryWidth));
                        }
                    }
                    view.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Intent intent = new Intent(App.context, PhotosActivity.class);
                            intent.putExtra(PhotosActivity.EXTRA_PHOTOS_DIR, photosDir);
                            intent.putExtra(PhotosActivity.EXTRA_MESSAGE_ID, messageID);
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
    }
}
