package com.klkblake.mm;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;


public class MainActivity extends Activity {
    private static final int REQUEST_TAKE_PHOTO = 1;
    private static final int REQUEST_SELECT_PHOTOS = 2;
    private ListView messageList;
    private EditText composeText;
    private Button sendButton;
    private MessageListAdapter messages;
    private File photoFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File externalFilesDir = getExternalFilesDir(null);
        photoFile = new File(externalFilesDir, "photo.jpg");
        setContentView(R.layout.activity_main);
        messageList = (ListView) findViewById(R.id.messageList);
        composeText = (EditText) findViewById(R.id.composeText);
        sendButton = (Button) findViewById(R.id.sendButton);
        messages = new MessageListAdapter(externalFilesDir);
        messageList.setAdapter(messages);
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
        Editable textContent = composeText.getText();
        String text = textContent.toString().trim();
        if (text.length() == 0) {
            return;
        }
        messages.add("user2", text);
        textContent.clear();
    }

    public void takePhoto(View view) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(photoFile));
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_TAKE_PHOTO);
        }
    }

    public void selectPhotos(View view) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_SELECT_PHOTOS);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_TAKE_PHOTO && resultCode == RESULT_OK) {
            String photoPath = photoFile.toString();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photoPath, options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = options.outWidth / messageList.getWidth();
            Bitmap photo = BitmapFactory.decodeFile(photoPath, options);
            messages.add("user2", photo, photoFile);
        }
        if (requestCode == REQUEST_SELECT_PHOTOS && resultCode == RESULT_OK) {
            ClipData selected = data.getClipData();
            int count;
            Bitmap[] photos;
            Uri[] photoUris;
            if (selected == null) {
                count = 1;
                photos = new Bitmap[1];
                photoUris = new Uri[1];
                photoUris[0] = data.getData();
            } else {
                count = selected.getItemCount();
                int numBitmaps = count;
                if (numBitmaps > MessageListAdapter.MAX_ALBUM_PREVIEW_PHOTOS) {
                    numBitmaps = MessageListAdapter.MAX_ALBUM_PREVIEW_PHOTOS;
                }
                photos = new Bitmap[numBitmaps];
                photoUris = new Uri[count];
                for (int i = 0; i < count; i++) {
                    photoUris[i] = selected.getItemAt(i).getUri();
                }
            }
            ContentResolver cr = getContentResolver();
            int columns;
            if (count == 1) {
                columns = 1;
            } else if (count <= 4) {
                columns = 2;
            } else {
                columns = 3;
            }
            for (int i = 0; i < photos.length; i++) {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(cr.openInputStream(photoUris[i]), null, options);
                    options.inJustDecodeBounds = false;
                    options.inSampleSize = options.outWidth / messageList.getWidth() * columns;
                    photos[i] = BitmapFactory.decodeStream(cr.openInputStream(photoUris[i]), null, options);
                } catch (FileNotFoundException e) {
                    couldntReadPhoto(i);
                    return;
                }
            }
            int failIndex = messages.add("user2", photos, photoUris, cr);
            if (failIndex != -1) {
                couldntReadPhoto(failIndex);
                return;
            }
        }
    }

    private void couldntReadPhoto(int i) {
        Toast.makeText(getApplicationContext(), "Could not read photo " + (i + 1), Toast.LENGTH_SHORT).show();
    }
}
