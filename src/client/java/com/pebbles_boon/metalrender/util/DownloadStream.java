package com.pebbles_boon.metalrender.util;

import java.util.function.LongConsumer;

public class DownloadStream {
  public void download(long deviceAddress, int size, LongConsumer callback) {
    callback.accept(0L);
  }
}
