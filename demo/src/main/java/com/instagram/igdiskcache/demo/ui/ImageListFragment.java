/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE-examples file in the root directory of this source tree.
 */

package com.instagram.igdiskcache.demo.ui;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.instagram.igdiskcache.demo.R;

/**
 * Main Fragment for the demo app. We use a RecyclerView here to hold all the image feeds.
 */
public class ImageListFragment extends Fragment {
  private static final String TAG = ImageListFragment.class.getSimpleName();
  protected final static String[] DATASET = {
      "https://www.instagram.com/p/cUS7ingBXj/media/?size=l",
      "https://www.instagram.com/p/c5b9AmgBTC/media/?size=l",
      "https://www.instagram.com/p/dJZZNTgBeY/media/?size=l",
      "https://www.instagram.com/p/f_CjHlABew/media/?size=l",
      "https://www.instagram.com/p/fkqGqjgBUT/media/?size=l",
      "https://www.instagram.com/p/fIvDbNABQL/media/?size=l",
      "https://www.instagram.com/p/edLmLPgBfu/media/?size=l",
      "https://www.instagram.com/p/d-SMOoABUO/media/?size=l",
      "https://www.instagram.com/p/Q0W-IgABed/media/?size=l",
      "https://www.instagram.com/p/RWB-x0gBbI/media/?size=l",
      "https://www.instagram.com/p/QdYwipABY-/media/?size=l",
      "https://www.instagram.com/p/NrUyZqgBVL/media/?size=l",
      "https://www.instagram.com/p/LR6OstgBfh/media/?size=l",
      "https://www.instagram.com/p/Kweua8ABR9/media/?size=l",
      "https://www.instagram.com/p/lnIqM/media/?size=l",
      "https://www.instagram.com/p/9W24rxgBYt/media/?size=l",
      "https://www.instagram.com/p/9vjk6iABbt/media/?size=l",
      "https://www.instagram.com/p/-DPpr3gBcb/media/?size=l",
      "https://www.instagram.com/p/BCzC1ApABS2/media/?size=l",
      "https://www.instagram.com/p/BDG1o9lgBWm/media/?size=l",
      "https://www.instagram.com/p/BD8M6S8ABR_/media/?size=l",
      "https://www.instagram.com/p/4nzjYdABST/media/?size=l"
  };
  protected RecyclerView mRecyclerView;
  protected ImageListAdapter mAdapter;
  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
                           Bundle savedInstanceState) {
    View rootView = inflater.inflate(R.layout.recycler_view_frag, container, false);
    rootView.setTag(TAG);
    mRecyclerView = (RecyclerView) rootView.findViewById(R.id.recyclerView);
    mAdapter = new ImageListAdapter(DATASET);
    mRecyclerView.setAdapter(mAdapter);
    mRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
    return rootView;
  }
}
