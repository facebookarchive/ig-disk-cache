/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE-examples file in the root directory of this source tree.
 */

package com.instagram.igdiskcache.demo.ui;

import android.content.Context;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentTransaction;
import android.os.Bundle;

import com.instagram.igdiskcache.demo.R;
import com.instagram.igdiskcache.demo.cache.ImageLoader;

/**
 * A simple image feed demo for IgDiskCache
 * Modify the {@link ImageLoader#init(Context, boolean)} settings to enable/disable the BitmapCache.
 */
public class MainActivity extends FragmentActivity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    ImageLoader.init(
        getApplicationContext(),
        true /*enableBitmapCache*/
    );
    setContentView(R.layout.activity_main);
    FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
    ImageListFragment fragment = new ImageListFragment();
    transaction.replace(R.id.sample_content_fragment, fragment);
    transaction.commit();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    ImageLoader.getInstance().close();
  }
}
