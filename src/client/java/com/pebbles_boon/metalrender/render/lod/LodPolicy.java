package com.pebbles_boon.metalrender.render.lod;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public final class LodPolicy {
  public enum Decision { KEEP, UPGRADE, DOWNGRADE }

  
  public static final int UPGRADE_CREDENTIAL_SCANS = 2;
  
  public static final int DOWNGRADE_HOLDOFF_SCANS = 40;
  
  public static final int STALE_DEMOTE_SCANS = 60;
  
  public static final float IN_VIEW_SCORE = 0.25f;
  
  public static final int STATE_MAX_AGE_SCANS = 900;
  
  public static final int PRUNE_INTERVAL_SCANS = 64;

  
  public static final int MAX_UPGRADES_PER_PASS = 16;
  
  public static final int BUDGET_TARGET_PENDING = 64;
  
  public static final double MESH_MS_HARD_CAP = 8.0;
  
  public static final double MESH_MS_EXTREME_CAP = 14.0;

  
  public static final int SKELETON_BACKLOG_MEDIUM = 384;
  
  public static final int SKELETON_BACKLOG_HEAVY = 1024;

  
  public static final int RECENCY_STALE_TICKS = 120;

  public static final class ChunkState {
    
    public int visibleStreak;
    
    public int staleScans;
    
    public int lastUpgradeScan = Integer.MIN_VALUE;
    
    public int lastTouchedScan = Integer.MIN_VALUE;
  }

  private final Long2ObjectOpenHashMap<ChunkState> states = new Long2ObjectOpenHashMap<>(8192);

  private int scanIndex;
  private int pruneCounter;
  private boolean visibilityGateEnabled = true;
  private boolean viewImpactEnabled = true;
  private boolean stickyEnabled = true;

  
  private int upgradesQueued;
  private int downgradesQueued;
  private int deferredNotVisible;
  private int deferredCredential;
  private int deferredBehindCamera;
  private int deferredHoldoff;
  private int deferredBusy;

  public void setEnabled(boolean visibilityGate, boolean viewImpact, boolean sticky) {
    this.visibilityGateEnabled = visibilityGate;
    this.viewImpactEnabled = viewImpact;
    this.stickyEnabled = sticky;
  }

  
  public int beginScan() {
    if (scanIndex == Integer.MAX_VALUE) {
      renormalizeStamps();
      scanIndex = 0;
    } else {
      scanIndex++;
    }
    return scanIndex;
  }

  
  public float computeViewScore(float dx, float dz, float forwardX, float forwardZ,
      float distSq) {
    float inv = (float) (1.0 / Math.sqrt((double) distSq + 1.0));
    float dot = (dx * inv) * forwardX + (dz * inv) * forwardZ;
    return Math.max(0.0f, dot);
  }

  
  public float computeUpgradeImpact(float viewScore, float distSq, int quadCount) {
    float proximity = 1.0f / (1.0f + distSq * 0.00005f);
    float weight = 1.0f + Math.min(1.0f, quadCount / 16384.0f);
    return viewScore * proximity * weight;
  }

  
  public int computeUpgradeBudget(int pending, int inFlight, double ewmaMeshMs) {
    int depth = pending + inFlight;
    double fillness = 1.0 - Math.min(1.0, Math.max(0.0,
        (double) depth / BUDGET_TARGET_PENDING));
    int budget = (int) Math.ceil(fillness * fillness * MAX_UPGRADES_PER_PASS);
    if (ewmaMeshMs >= MESH_MS_EXTREME_CAP) {
      budget = Math.min(budget, 1);
    } else if (ewmaMeshMs >= MESH_MS_HARD_CAP) {
      budget = Math.min(budget, 2);
    }
    return budget;
  }

  
  public int computeSkeletonTierCap(int pending, int inFlight) {
    int backlog = pending + inFlight;
    if (backlog >= SKELETON_BACKLOG_HEAVY) {
      return 2;
    }
    if (backlog >= SKELETON_BACKLOG_MEDIUM) {
      return 1;
    }
    return 0;
  }

  
  public Decision observeAndDecide(long key, int currentTier, int ringTier,
      boolean visible, float viewScore, boolean demotionIdle) {
    ChunkState state = states.get(key);
    if (state == null) {
      state = new ChunkState();
      states.put(key, state);
    }
    state.lastTouchedScan = scanIndex;

    if (visible) {
      state.visibleStreak = state.visibleStreak < Integer.MAX_VALUE
          ? state.visibleStreak + 1 : state.visibleStreak;
      state.staleScans = 0;
    } else {
      state.visibleStreak = 0;
      state.staleScans = state.staleScans < Integer.MAX_VALUE
          ? state.staleScans + 1 : state.staleScans;
    }

    Decision decision = Decision.KEEP;
    if (ringTier < currentTier) {
      decision = decideUpgrade(state, visible, viewScore);
    } else if (ringTier > currentTier) {
      decision = decideDemotion(state, visible, viewScore, demotionIdle);
    }

    if (decision == Decision.UPGRADE) {
      state.lastUpgradeScan = scanIndex;
    }

    if (--pruneCounter <= 0) {
      pruneCounter = PRUNE_INTERVAL_SCANS;
      pruneStaleState();
    }
    return decision;
  }

  private Decision decideUpgrade(ChunkState state, boolean visible, float viewScore) {
    if (visibilityGateEnabled && !visible) {
      deferredNotVisible++;
      return Decision.KEEP;
    }
    if (viewImpactEnabled && viewScore <= 0.0f) {
      deferredBehindCamera++;
      return Decision.KEEP;
    }
    if (visibilityGateEnabled && state.visibleStreak < UPGRADE_CREDENTIAL_SCANS) {
      deferredCredential++;
      return Decision.KEEP;
    }
    upgradesQueued++;
    return Decision.UPGRADE;
  }

  private Decision decideDemotion(ChunkState state, boolean visible,
      float viewScore, boolean demotionIdle) {
    boolean stale = state.staleScans >= STALE_DEMOTE_SCANS;
    if (stale) {
      downgradesQueued++;
      return Decision.DOWNGRADE;
    }
    if (stickyEnabled) {
      boolean inView = viewImpactEnabled
          ? (visible && viewScore >= IN_VIEW_SCORE)
          : visible;
      if (inView) {
        deferredHoldoff++;
        return Decision.KEEP;
      }
      boolean holdoffExpired = state.lastUpgradeScan == Integer.MIN_VALUE
          || (scanIndex - state.lastUpgradeScan) >= DOWNGRADE_HOLDOFF_SCANS;
      if (!holdoffExpired) {
        deferredHoldoff++;
        return Decision.KEEP;
      }
    }
    if (!demotionIdle) {
      deferredBusy++;
      return Decision.KEEP;
    }
    downgradesQueued++;
    return Decision.DOWNGRADE;
  }

  private void pruneStaleState() {
    if (states.isEmpty()) {
      return;
    }
    var iter = states.long2ObjectEntrySet().fastIterator();
    while (iter.hasNext()) {
      var entry = iter.next();
      int touched = entry.getValue().lastTouchedScan;
      int age = touched == Integer.MIN_VALUE ? Integer.MAX_VALUE
          : scanIndex - touched;
      if (age >= STATE_MAX_AGE_SCANS) {
        iter.remove();
      }
    }
  }

  
  private void renormalizeStamps() {
    var iter = states.long2ObjectEntrySet().fastIterator();
    while (iter.hasNext()) {
      ChunkState s = iter.next().getValue();
      if (s.lastTouchedScan != Integer.MIN_VALUE) {
        s.lastTouchedScan -= scanIndex;
      }
      if (s.lastUpgradeScan != Integer.MIN_VALUE) {
        s.lastUpgradeScan -= scanIndex;
      }
    }
  }

  
  public void clear() {
    states.clear();
    scanIndex = 0;
    pruneCounter = 0;
  }

  public int getStateCount() {
    return states.size();
  }

  public int getUpgradesQueued() {
    return upgradesQueued;
  }

  public int getDowngradesQueued() {
    return downgradesQueued;
  }

  public int getDeferredNotVisible() {
    return deferredNotVisible;
  }

  public int getDeferredCredential() {
    return deferredCredential;
  }

  public int getDeferredBehindCamera() {
    return deferredBehindCamera;
  }

  public int getDeferredHoldoff() {
    return deferredHoldoff;
  }

  public int getDeferredBusy() {
    return deferredBusy;
  }

  
  public void resetDiagnostics() {
    upgradesQueued = 0;
    downgradesQueued = 0;
    deferredNotVisible = 0;
    deferredCredential = 0;
    deferredBehindCamera = 0;
    deferredHoldoff = 0;
    deferredBusy = 0;
  }
}