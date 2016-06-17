/*
 * Copyright (c) 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.instagram.igdiskcache;

import org.junit.Rule;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.powermock.modules.junit4.rule.PowerMockRule;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

/**
 * Base class for all Robolectric test in this project.
 */
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 21)
@PowerMockIgnore({"org.mockito.*", "org.robolectric.*", "android.*"})
public abstract class RobolectricBaseTest {
  @Rule
  public PowerMockRule rule = new PowerMockRule();
}
