package com.klkblake.mm;

import java.util.ArrayList;
import java.util.Locale;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import android.support.v13.app.FragmentPagerAdapter;
import android.os.Bundle;
import android.support.v13.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

public class AlbumActivity extends AppActivity {
    public static final String EXTRA_PHOTO_URIS = "photo_uris";
    private ViewPager pager;
    private Uri[] photoUris;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album);
        ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_PHOTO_URIS);
        photoUris = uris.toArray(new Uri[uris.size()]);
        AlbumPagerAdapter adapter = new AlbumPagerAdapter(getFragmentManager());
        pager = (ViewPager) findViewById(R.id.pager);
        pager.setAdapter(adapter);
        setTitle("Viewing " + Integer.toString(photoUris.length) + " photos");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_album, menu);
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
                intent.putExtra(Intent.EXTRA_STREAM, photoUris[pager.getCurrentItem()]);
                startActivity(Intent.createChooser(intent, "Share with"));
                return true;
            }
            case R.id.action_view: {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setDataAndType(photoUris[pager.getCurrentItem()], "image/jpeg");
                // TODO decide whether to use a chooser here
                startActivity(Intent.createChooser(intent, "View in"));
                //if (intent.resolveActivity(getPackageManager()) != null) {
                //    startActivity(intent);
                //}
                return true;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    public class AlbumPagerAdapter extends FragmentStatePagerAdapter {
        public AlbumPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            return PhotoFragment.newInstance(photoUris[position]);
        }

        @Override
        public int getCount() {
            return photoUris.length;
        }
    }

    public static class PhotoFragment extends Fragment {
        private static final String ARG_IMAGE_URI = "image_uri";

        public static PhotoFragment newInstance(Uri photoUri) {
            PhotoFragment fragment = new PhotoFragment();
            Bundle args = new Bundle();
            args.putParcelable(ARG_IMAGE_URI, photoUri);
            fragment.setArguments(args);
            return fragment;
        }

        public PhotoFragment() {
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            ImageView view = new ImageView(container.getContext());
            Bundle args = getArguments();
            // FIXME latency
            view.setImageURI(args.<Uri>getParcelable(ARG_IMAGE_URI));
            return view;
        }
    }
}
