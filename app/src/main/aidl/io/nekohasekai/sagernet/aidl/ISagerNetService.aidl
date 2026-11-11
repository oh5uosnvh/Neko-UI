package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);
  oneway void resetTraffic(in long[] profileIds);

  int urlTest();
  String getConnectionProbe();

  /** Hot-switch the running native automatic configuration. Returns a JSON receipt. */
  String setGlobalMode(boolean desired);
  /** Returns a JSON snapshot of the core's actual runtime mode. */
  String getGlobalMode();
}
