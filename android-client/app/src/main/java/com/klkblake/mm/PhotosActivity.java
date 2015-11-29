package com.klkblake.mm;

import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.os.Bundle;
import android.support.v13.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

public class PhotosActivity extends AppActivity {
    public static final String EXTRA_PHOTOS_DIR = "photo_dir";
    public static final String EXTRA_MESSAGE_ID = "message_id";
    public static final String EXTRA_PHOTO_COUNT = "photo_count";
    private ViewPager pager;
    private ImageLoaderThread loader;
    private int count;
    private int messageID;
    private String photosDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photos);
        Intent intent = getIntent();
        photosDir = intent.getStringExtra(EXTRA_PHOTOS_DIR);
        messageID = intent.getIntExtra(EXTRA_MESSAGE_ID, -1);
        count = intent.getIntExtra(EXTRA_PHOTO_COUNT, -1);
        if (messageID == -1 || count == -1) {
            throw new IllegalArgumentException("Invalid messageID or photo count");
        }
        PhotosPagerAdapter adapter = new PhotosPagerAdapter(getFragmentManager());
        pager = (ViewPager) findViewById(R.id.pager);
        pager.setAdapter(adapter);
        loader = new ImageLoaderThread(photosDir, pager, adapter);
        loader.start();
        setTitle("Viewing " + count + " photos");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_photos, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        switch (id) {
            case R.id.action_share: {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setType("image/jpeg");
                intent.putExtra(Intent.EXTRA_STREAM, App.getUriForPath(photosDir + "/" + messageID + "/" + pager.getCurrentItem() + ".jpg"));
                startActivity(Intent.createChooser(intent, "Share with"));
                return true;
            }
            case R.id.action_view: {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setDataAndType(App.getUriForPath(photosDir + "/" + messageID + "/" + pager.getCurrentItem() + ".jpg"), "image/jpeg");
                startActivity(Intent.createChooser(intent, "View in"));
                return true;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    public class PhotosPagerAdapter extends FragmentStatePagerAdapter {
        public PhotosPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            Fragment fragment = new PhotoFragment();
            Bundle args = new Bundle();
            args.putInt(PhotoFragment.ARG_PART_ID, position);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public int getCount() {
            return count;
        }
    }

    public static class PhotoFragment extends Fragment {
        private static final String ARG_PART_ID = "part_id";

        public PhotoFragment() {
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            ImageView view = new ImageView(container.getContext());
            Bundle args = getArguments();
            PhotosActivity activity = (PhotosActivity) getActivity();
            view.setImageBitmap(activity.loader.request(activity.messageID, args.getInt(ARG_PART_ID), false, container.getWidth()));
            return view;
        }
    }
}
