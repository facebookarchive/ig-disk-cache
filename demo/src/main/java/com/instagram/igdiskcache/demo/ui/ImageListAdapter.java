/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE-examples file in the root directory of this source tree.
 */

package com.instagram.igdiskcache.demo.ui;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.instagram.igdiskcache.demo.R;
import com.instagram.igdiskcache.demo.cache.ImageLoader;

/**
 * ImageListAdapter is the place where images are loaded into the target ImageView asynchronously.
 * Modify the {@link ImageListAdapter#IMAGE_HEIGHT} and {@link ImageListAdapter#IMAGE_WIDTH} to
 * resize the images.
 */
public class ImageListAdapter extends RecyclerView.Adapter<ImageListAdapter.ViewHolder> {
  private static int IMAGE_HEIGHT = 700;
  private static int IMAGE_WIDTH = 700;
  private String[] mDataSet;

  public static class ViewHolder extends RecyclerView.ViewHolder {
    private final ImageView imageView;

    public ViewHolder(View v) {
      super(v);
      imageView = (ImageView) v.findViewById(R.id.imageView);
    }

    public ImageView getImageView() {
      return imageView;
    }
  }

  public ImageListAdapter(String[] dataSet) {
    mDataSet = dataSet;
  }

  @Override
  public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
    View v = LayoutInflater.from(viewGroup.getContext())
        .inflate(R.layout.image_row_item, viewGroup, false);
    return new ViewHolder(v);
  }

  @Override
  public void onBindViewHolder(ViewHolder viewHolder, final int position) {
    ImageLoader.getInstance().loadImage(
        mDataSet[position],
        viewHolder.getImageView(),
        IMAGE_HEIGHT,
        IMAGE_WIDTH,
        R.drawable.empty_photo);
  }

  @Override
  public int getItemCount() {
    return mDataSet.length;
  }
}
