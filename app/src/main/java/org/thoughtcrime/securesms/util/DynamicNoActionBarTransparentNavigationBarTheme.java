package org.thoughtcrime.securesms.util;

import androidx.annotation.StyleRes;

import org.thoughtcrime.securesms.R;

public class DynamicNoActionBarTransparentNavigationBarTheme extends DynamicTheme {

  protected @StyleRes int getTheme() {
    return R.style.Signal_DayNight_NoActionBar_TransparentNavigationBar;
  }
}
