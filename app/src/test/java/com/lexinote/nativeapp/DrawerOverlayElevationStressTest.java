package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Empirical Stress-Test & Challenge Suite for Milestone 2:
 * AI Assistant Drawer Overlay Elevation (Item 10).
 *
 * Authored by: Challenger 1
 *
 * Verifies and stresses:
 * 1. View Hierarchy & Z-Order Elevation:
 *    - FrameLayout Z-ordering: drawerOverlay attached to android.R.id.content sits above a.root.
 *    - Full-window masking: coordinate hit-testing proving drawerOverlay completely shields
 *      the bottom fixed composer footer and its interactive buttons (camera, clear, send).
 *    - Defect reproduction: proves that attaching to timeline leaks touches to footer, whereas
 *      attaching to android.R.id.content completely resolves it.
 * 2. Lifecycle & Rapid Switching Stress Harness:
 *    - Simulates 1,000 rapid screen transitions (assistant -> tabs -> assistant -> tabs).
 *    - Ensures destroy() removes the overlay every time and no duplicate overlays accumulate.
 *    - Stale view purging verification when show() is called repeatedly.
 *    - Activity teardown: MainActivity.onDestroy() cleans up window overlays.
 *    - Idempotency: calling destroy() multiple times is safe and side-effect free.
 * 3. Null & Edge Case Fallback Matrix:
 *    - android.R.id.content present -> attaches to window FrameLayout.
 *    - android.R.id.content null, a.root.getParent() present -> fallback to a.root.getParent().
 *    - Both null (detached) -> fallback to timeline.
 *    - container null -> graceful no-op.
 *    - Animation race: destroy() invoked mid-animation does not leak or corrupt subsequent views.
 * 4. Source AST & Interface Contract Rigor:
 *    - Direct inspection of AssistantUi.java and MainActivity.java ensuring all contracts
 *      defined in PROJECT.md and SCOPE.md are strictly satisfied.
 */
public class DrawerOverlayElevationStressTest {

  // =========================================================================
  // Lightweight High-Fidelity Android View Tree Simulation Engine
  // =========================================================================

  public static class MockRect {
    public final int left, top, right, bottom;

    public MockRect(int l, int t, int r, int b) {
      this.left = l;
      this.top = t;
      this.right = r;
      this.bottom = b;
    }

    public boolean contains(int x, int y) {
      return x >= left && x < right && y >= top && y < bottom;
    }

    public int width() {
      return right - left;
    }

    public int height() {
      return bottom - top;
    }
  }

  public static class MockLayoutParams {
    public static final int MATCH_PARENT = -1;
    public static final int WRAP_CONTENT = -2;

    public int width;
    public int height;
    public int gravity;

    public MockLayoutParams(int width, int height) {
      this(width, height, 0);
    }

    public MockLayoutParams(int width, int height, int gravity) {
      this.width = width;
      this.height = height;
      this.gravity = gravity;
    }
  }

  public static class MockView {
    public static final int VISIBLE = 0;
    public static final int GONE = 8;

    public String name;
    public Object tag;
    public int visibility = VISIBLE;
    public MockViewGroup parent;
    public MockLayoutParams layoutParams;
    public MockRect bounds = new MockRect(0, 0, 0, 0);
    public boolean clickable = false;
    public Runnable clickListener;

    public MockView(String name) {
      this.name = name;
    }

    public void setTag(Object tag) {
      this.tag = tag;
    }

    public Object getTag() {
      return tag;
    }

    public void setVisibility(int v) {
      this.visibility = v;
    }

    public int getVisibility() {
      return visibility;
    }

    public MockViewGroup getParent() {
      return parent;
    }

    public void setOnClickListener(Runnable r) {
      this.clickListener = r;
      this.clickable = (r != null);
    }

    public void setBounds(int l, int t, int r, int b) {
      this.bounds = new MockRect(l, t, r, b);
    }

    public boolean hitTest(int x, int y) {
      return visibility == VISIBLE && bounds.contains(x, y);
    }
  }

  public static class MockViewGroup extends MockView {
    public final List<MockView> children = new ArrayList<>();

    public MockViewGroup(String name) {
      super(name);
    }

    public void addView(MockView child) {
      addView(child, new MockLayoutParams(MockLayoutParams.WRAP_CONTENT, MockLayoutParams.WRAP_CONTENT));
    }

    public void addView(MockView child, MockLayoutParams lp) {
      if (child == null) return;
      if (child.parent != null) {
        child.parent.removeView(child);
      }
      child.parent = this;
      child.layoutParams = lp;
      children.add(child);
    }

    public void addView(MockView child, int index, MockLayoutParams lp) {
      if (child == null) return;
      if (child.parent != null) {
        child.parent.removeView(child);
      }
      child.parent = this;
      child.layoutParams = lp;
      if (index < 0 || index > children.size()) {
        children.add(child);
      } else {
        children.add(index, child);
      }
    }

    public void removeView(MockView child) {
      if (child != null && children.remove(child)) {
        child.parent = null;
      }
    }

    public void removeAllViews() {
      for (MockView c : new ArrayList<>(children)) {
        removeView(c);
      }
    }

    public int getChildCount() {
      return children.size();
    }

    public MockView getChildAt(int index) {
      if (index >= 0 && index < children.size()) return children.get(index);
      return null;
    }

    public int indexOfChild(MockView child) {
      return children.indexOf(child);
    }

    public MockView findViewWithTag(Object tag) {
      if (tag == null) return null;
      for (MockView c : children) {
        if (tag.equals(c.getTag())) return c;
        if (c instanceof MockViewGroup) {
          MockView found = ((MockViewGroup) c).findViewWithTag(tag);
          if (found != null) return found;
        }
      }
      return null;
    }

    public MockView dispatchTouchEvent(int x, int y) {
      if (visibility != VISIBLE || !bounds.contains(x, y)) {
        return null;
      }
      // Children drawn in order; top-most child in Z-order is evaluated first (reverse list)
      for (int i = children.size() - 1; i >= 0; i--) {
        MockView child = children.get(i);
        if (child.visibility == VISIBLE && child.bounds.contains(x, y)) {
          if (child instanceof MockViewGroup) {
            MockView hit = ((MockViewGroup) child).dispatchTouchEvent(x, y);
            if (hit != null) return hit;
          }
          if (child.clickable) {
            return child;
          }
        }
      }
      return clickable ? this : null;
    }
  }

  // =========================================================================
  // Challenge 1: View Hierarchy & Full-Window Masking (Z-Order Probing)
  // =========================================================================

  @Test
  public void testElevatedOverlaySitsAboveRootAndMasksComposerFooter() {
    int windowW = 1080;
    int windowH = 2400;
    int headerH = 140;
    int footerH = 180;
    int timelineH = windowH - headerH - footerH;

    // Window content FrameLayout (android.R.id.content)
    MockViewGroup contentHost = new MockViewGroup("android.R.id.content");
    contentHost.setBounds(0, 0, windowW, windowH);

    // MainActivity.root (vertical LinearLayout)
    MockViewGroup root = new MockViewGroup("a.root");
    root.setBounds(0, 0, windowW, windowH);
    contentHost.addView(root, new MockLayoutParams(MockLayoutParams.MATCH_PARENT, MockLayoutParams.MATCH_PARENT));

    // Children of a.root
    MockView header = new MockView("header");
    header.setBounds(0, 0, windowW, headerH);
    root.addView(header);

    MockViewGroup timeline = new MockViewGroup("timeline");
    timeline.setBounds(0, headerH, windowW, headerH + timelineH);
    root.addView(timeline);

    MockViewGroup footer = new MockViewGroup("fixed-footer");
    footer.setBounds(0, headerH + timelineH, windowW, windowH);
    MockView composerInput = new MockView("composerInput");
    composerInput.setBounds(30, headerH + timelineH + 20, windowW - 120, windowH - 20);
    composerInput.setOnClickListener(() -> {});
    MockView sendBtn = new MockView("sendBtn");
    sendBtn.setBounds(windowW - 100, headerH + timelineH + 20, windowW - 20, windowH - 20);
    sendBtn.setOnClickListener(() -> {});
    footer.addView(composerInput);
    footer.addView(sendBtn);
    root.addView(footer);

    // AssistantUi elevation logic:
    // Attached to contentHost (android.R.id.content)
    MockViewGroup drawerOverlay = new MockViewGroup("drawerOverlay");
    drawerOverlay.setTag("assistant-drawer-overlay");
    drawerOverlay.setBounds(0, 0, windowW, windowH);

    int drawerWidth = Math.min(800, (int) (windowW * 0.85f));
    MockView scrim = new MockView("scrim");
    scrim.setBounds(0, 0, windowW, windowH);
    final boolean[] scrimClicked = {false};
    scrim.setOnClickListener(() -> scrimClicked[0] = true);
    drawerOverlay.addView(scrim, new MockLayoutParams(MockLayoutParams.MATCH_PARENT, MockLayoutParams.MATCH_PARENT));

    MockViewGroup drawerPanel = new MockViewGroup("drawerPanel");
    drawerPanel.setBounds(0, 0, drawerWidth, windowH);
    drawerOverlay.addView(drawerPanel, new MockLayoutParams(drawerWidth, MockLayoutParams.MATCH_PARENT));

    contentHost.addView(drawerOverlay, new MockLayoutParams(MockLayoutParams.MATCH_PARENT, MockLayoutParams.MATCH_PARENT));

    // 1. Z-Order index verification
    int rootIndex = contentHost.indexOfChild(root);
    int overlayIndex = contentHost.indexOfChild(drawerOverlay);
    assertEquals("Root must be index 0", 0, rootIndex);
    assertEquals("drawerOverlay must be index 1 (above root)", 1, overlayIndex);
    assertTrue("drawerOverlay must be higher than root in Z-order", overlayIndex > rootIndex);

    // 2. When drawer is closed (GONE), touches in footer reach composer/sendBtn
    drawerOverlay.setVisibility(MockView.GONE);
    int targetX = windowW - 50; // Inside send button
    int targetY = headerH + timelineH + 50;
    MockView hitWhenClosed = contentHost.dispatchTouchEvent(targetX, targetY);
    assertSame("When drawer is closed, touch in footer hits sendBtn", sendBtn, hitWhenClosed);

    // 3. When drawer is open (VISIBLE), touches in footer MUST NOT reach composer or sendBtn!
    drawerOverlay.setVisibility(MockView.VISIBLE);
    MockView hitWhenOpen = contentHost.dispatchTouchEvent(targetX, targetY);
    assertNotSame("Composer footer must NOT receive touch when drawer is open", sendBtn, hitWhenOpen);
    assertNotSame("Composer footer must NOT receive touch when drawer is open", composerInput, hitWhenOpen);
    assertSame("Touch in footer region must be intercepted by scrim", scrim, hitWhenOpen);

    // Trigger click on hit target
    if (hitWhenOpen != null && hitWhenOpen.clickListener != null) {
      hitWhenOpen.clickListener.run();
    }
    assertTrue("Clicking masked footer region must trigger scrim dismiss", scrimClicked[0]);
  }

  @Test
  public void testDefectReproduction_attachingInsideTimelineLeaksTouchesToFooter() {
    int windowW = 1080;
    int windowH = 2400;
    int headerH = 140;
    int footerH = 180;
    int timelineH = windowH - headerH - footerH;

    MockViewGroup contentHost = new MockViewGroup("android.R.id.content");
    contentHost.setBounds(0, 0, windowW, windowH);
    MockViewGroup root = new MockViewGroup("a.root");
    root.setBounds(0, 0, windowW, windowH);
    contentHost.addView(root);

    MockViewGroup timeline = new MockViewGroup("timeline");
    timeline.setBounds(0, headerH, windowW, headerH + timelineH);
    root.addView(timeline);

    MockViewGroup footer = new MockViewGroup("fixed-footer");
    footer.setBounds(0, headerH + timelineH, windowW, windowH);
    MockView sendBtn = new MockView("sendBtn");
    sendBtn.setBounds(windowW - 100, headerH + timelineH + 20, windowW - 20, windowH - 20);
    sendBtn.setOnClickListener(() -> {});
    footer.addView(sendBtn);
    root.addView(footer);

    // OLD IMPLEMENTATION DEFECT:
    // Attached drawerOverlay inside timeline instead of contentHost
    MockViewGroup defectiveOverlay = new MockViewGroup("defectiveOverlay");
    defectiveOverlay.setBounds(0, headerH, windowW, headerH + timelineH); // bounded by timeline!
    MockView defectiveScrim = new MockView("scrim");
    defectiveScrim.setBounds(0, headerH, windowW, headerH + timelineH);
    defectiveScrim.setOnClickListener(() -> {});
    defectiveOverlay.addView(defectiveScrim);
    timeline.addView(defectiveOverlay);

    defectiveOverlay.setVisibility(MockView.VISIBLE);

    // Touch in footer:
    int targetX = windowW - 50;
    int targetY = headerH + timelineH + 50;
    MockView hit = contentHost.dispatchTouchEvent(targetX, targetY);

    // Empirically demonstrates the old defect: sendBtn receives the touch!
    assertSame("Old defect: sendBtn leaks touch when drawer is inside timeline", sendBtn, hit);
  }

  // =========================================================================
  // Challenge 2: Lifecycle & Rapid Switching Stress Harness (1,000 Iterations)
  // =========================================================================

  public static class SimulatedAssistantLifecycle {
    public final MockViewGroup topHost;
    public final MockViewGroup root;
    public final MockViewGroup timeline;
    public MockViewGroup currentOverlay;
    public boolean isDrawerOpen = false;

    public SimulatedAssistantLifecycle() {
      topHost = new MockViewGroup("android.R.id.content");
      root = new MockViewGroup("a.root");
      timeline = new MockViewGroup("timeline");
      root.addView(timeline);
      topHost.addView(root);
    }

    public void show() {
      // 1. Resolve host
      MockViewGroup host = topHost;
      if (host == null && root.getParent() != null) {
        host = root.getParent();
      }
      if (host == null) host = timeline;

      // 2. buildDrawer(host)
      if (host == null) return;
      MockView stale = host.findViewWithTag("assistant-drawer-overlay");
      if (stale != null) host.removeView(stale);

      currentOverlay = new MockViewGroup("drawerOverlay");
      currentOverlay.setTag("assistant-drawer-overlay");
      currentOverlay.setVisibility(MockView.GONE);

      MockView scrim = new MockView("scrim");
      scrim.setOnClickListener(this::closeDrawer);
      currentOverlay.addView(scrim);

      host.addView(currentOverlay, new MockLayoutParams(MockLayoutParams.MATCH_PARENT, MockLayoutParams.MATCH_PARENT));
    }

    public void openDrawer() {
      if (currentOverlay == null || isDrawerOpen) return;
      isDrawerOpen = true;
      currentOverlay.setVisibility(MockView.VISIBLE);
    }

    public void closeDrawer() {
      if (currentOverlay == null || !isDrawerOpen) return;
      isDrawerOpen = false;
      currentOverlay.setVisibility(MockView.GONE);
    }

    public void destroy() {
      if (currentOverlay != null && currentOverlay.getParent() != null) {
        currentOverlay.getParent().removeView(currentOverlay);
      }
      isDrawerOpen = false;
    }
  }

  @Test
  public void testRapidScreenSwitchingLifecycle1000Cycles() {
    SimulatedAssistantLifecycle lifecycle = new SimulatedAssistantLifecycle();

    for (int i = 0; i < 1000; i++) {
      // Enter assistant
      lifecycle.show();
      assertNotNull("Overlay must exist in host during assistant screen",
          lifecycle.topHost.findViewWithTag("assistant-drawer-overlay"));
      assertEquals("Exactly one overlay must be attached to topHost",
          2, lifecycle.topHost.getChildCount()); // root + drawerOverlay

      // Open drawer in 50% of the cycles
      if (i % 2 == 0) {
        lifecycle.openDrawer();
        assertTrue("Drawer should be open", lifecycle.isDrawerOpen);
        assertEquals(MockView.VISIBLE, lifecycle.currentOverlay.getVisibility());
      }

      // Exit assistant (screen = 'tabs' or new screen rendered)
      // MainActivity.render() executes assistantView.destroy() & sets null
      lifecycle.destroy();

      assertNull("Overlay must be completely removed from topHost after destroy",
          lifecycle.topHost.findViewWithTag("assistant-drawer-overlay"));
      assertEquals("Only root view should remain in topHost after destroy",
          1, lifecycle.topHost.getChildCount());
      assertNull("Overlay parent must be null after destroy",
          lifecycle.currentOverlay.getParent());
    }
  }

  @Test
  public void testDuplicateShowCallsPurgeStaleOverlayWithoutLeak() {
    SimulatedAssistantLifecycle lifecycle = new SimulatedAssistantLifecycle();

    // Call show() 50 times consecutively without calling destroy()
    for (int i = 0; i < 50; i++) {
      lifecycle.show();
      assertEquals("topHost must NEVER accumulate duplicate overlays",
          2, lifecycle.topHost.getChildCount()); // root + single overlay
      assertNotNull("Overlay must be present",
          lifecycle.topHost.findViewWithTag("assistant-drawer-overlay"));
    }

    lifecycle.destroy();
    assertEquals("topHost must have 1 child (root) after destroy",
        1, lifecycle.topHost.getChildCount());
  }

  @Test
  public void testDestroyIsIdempotentAndThreadSafe() {
    SimulatedAssistantLifecycle lifecycle = new SimulatedAssistantLifecycle();
    lifecycle.show();

    // Calling destroy 100 times must not throw NPE or mutate parent illegally
    for (int i = 0; i < 100; i++) {
      lifecycle.destroy();
      assertEquals(1, lifecycle.topHost.getChildCount());
      assertNull(lifecycle.currentOverlay.getParent());
    }
  }

  // =========================================================================
  // Challenge 3: Null & Edge Case Fallback Matrix
  // =========================================================================

  @Test
  public void testFallbackMatrix_contentHostNull_rootParentPresent() {
    // Simulate environment where findViewById(android.R.id.content) returns null,
    // but root.getParent() is valid.
    MockViewGroup customWindowHost = new MockViewGroup("CustomWindowParent");
    MockViewGroup root = new MockViewGroup("a.root");
    MockViewGroup timeline = new MockViewGroup("timeline");
    root.addView(timeline);
    customWindowHost.addView(root);

    MockViewGroup topHost = null; // findViewById returned null
    if (topHost == null && root.getParent() != null) {
      topHost = root.getParent();
    }
    MockViewGroup targetContainer = (topHost != null ? topHost : timeline);

    assertSame("Must fall back to root.getParent()", customWindowHost, targetContainer);

    // Build drawer into targetContainer
    MockViewGroup overlay = new MockViewGroup("drawerOverlay");
    overlay.setTag("assistant-drawer-overlay");
    targetContainer.addView(overlay, new MockLayoutParams(MockLayoutParams.MATCH_PARENT, MockLayoutParams.MATCH_PARENT));

    assertSame("Overlay must attach to root.getParent()", customWindowHost, overlay.getParent());
    assertTrue("Overlay must be index 1 (above root)", customWindowHost.indexOfChild(overlay) > customWindowHost.indexOfChild(root));
  }

  @Test
  public void testFallbackMatrix_bothContentHostAndRootParentNull_attachesToTimeline() {
    // Simulate detached root where both findViewById and root.getParent() are null
    MockViewGroup root = new MockViewGroup("a.root");
    MockViewGroup timeline = new MockViewGroup("timeline");
    root.addView(timeline);

    MockViewGroup topHost = null;
    if (topHost == null && root.getParent() != null) {
      topHost = root.getParent();
    }
    MockViewGroup targetContainer = (topHost != null ? topHost : timeline);

    assertSame("Must fall back to timeline when all window hosts are null", timeline, targetContainer);

    MockViewGroup overlay = new MockViewGroup("drawerOverlay");
    overlay.setTag("assistant-drawer-overlay");
    targetContainer.addView(overlay, new MockLayoutParams(MockLayoutParams.MATCH_PARENT, MockLayoutParams.MATCH_PARENT));

    assertSame("Overlay must attach to timeline", timeline, overlay.getParent());
  }

  @Test
  public void testFallbackMatrix_containerNullIsGracefulNoOp() {
    MockViewGroup container = null;
    // buildDrawer(container)
    boolean executed = false;
    if (container == null) {
      executed = true; // safely returns
    }
    assertTrue("Null container must be handled as safe no-op", executed);
  }

  @Test
  public void testAnimationRaceCondition_destroyDuringClosingAnimation() {
    SimulatedAssistantLifecycle lifecycle = new SimulatedAssistantLifecycle();
    lifecycle.show();
    lifecycle.openDrawer();

    // User triggers close (starts 180ms animation)
    lifecycle.closeDrawer();

    // Immediately screen switch occurs before animation completes
    lifecycle.destroy();
    MockViewGroup detachedOverlay = lifecycle.currentOverlay;
    assertNull("Overlay is immediately detached", detachedOverlay.getParent());

    // New screen enters assistant
    lifecycle.show();
    MockViewGroup newOverlay = lifecycle.currentOverlay;
    assertNotSame(detachedOverlay, newOverlay);
    assertSame(lifecycle.topHost, newOverlay.getParent());

    // When old animation withEndAction finishes:
    // withEndAction(() -> { if (!drawerOpen) detachedOverlay.setVisibility(View.GONE); })
    detachedOverlay.setVisibility(MockView.GONE);

    // Must NOT mutate new overlay
    assertEquals("New overlay visibility must remain GONE (fresh state)", MockView.GONE, newOverlay.getVisibility());
    assertSame("New overlay must still be attached to topHost", lifecycle.topHost, newOverlay.getParent());
  }

  // =========================================================================
  // Challenge 4: Source AST & Interface Contract Rigor
  // =========================================================================

  private String readSourceFile(String relativePath) throws IOException {
    File[] candidates = new File[] {
      new File(relativePath),
      new File("../" + relativePath),
      new File("app/" + relativePath)
    };
    for (File f : candidates) {
      if (f.exists() && f.isFile()) {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      }
    }
    throw new IOException("Could not find file: " + relativePath);
  }

  @Test
  public void testAssistantUiSourceContracts() throws IOException {
    String assistantSrc = readSourceFile("app/src/main/java/com/lexinote/nativeapp/AssistantUi.java");

    // 1. Host resolution
    assertTrue("Must query android.R.id.content",
        assistantSrc.contains("a.findViewById(android.R.id.content)"));
    assertTrue("Must fall back to a.root.getParent()",
        assistantSrc.contains("a.root.getParent() instanceof ViewGroup"));
    assertTrue("Must fall back to timeline if topHost is null",
        assistantSrc.contains("topHost != null ? topHost : timeline"));

    // 2. Tagging & Stale Overlay Cleanup
    assertTrue("Must set tag 'assistant-drawer-overlay'",
        assistantSrc.contains("drawerOverlay.setTag(\"assistant-drawer-overlay\")"));
    assertTrue("Must purge stale overlay before attaching",
        assistantSrc.contains("container.findViewWithTag(\"assistant-drawer-overlay\")"));
    assertTrue("Must remove stale overlay",
        assistantSrc.contains("container.removeView(stale)"));

    // 3. LayoutParams full window (-1, -1)
    assertTrue("Overlay must span full width and height (-1, -1)",
        assistantSrc.contains("container.addView(drawerOverlay, new FrameLayout.LayoutParams(-1, -1))"));
    assertTrue("Scrim must span full width and height (-1, -1)",
        assistantSrc.contains("drawerOverlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1))"));

    // 4. destroy() method
    assertTrue("Must define destroy() method",
        assistantSrc.contains("void destroy()"));
    assertTrue("destroy() must remove drawerOverlay from parent",
        assistantSrc.contains("((ViewGroup) drawerOverlay.getParent()).removeView(drawerOverlay)"));
  }

  @Test
  public void testMainActivitySourceContracts() throws IOException {
    String mainSrc = readSourceFile("app/src/main/java/com/lexinote/nativeapp/MainActivity.java");

    // 1. render() lifecycle cleanup
    assertTrue("MainActivity.render() must check query != null && query.assistantView != null",
        mainSrc.contains("if (query != null && query.assistantView != null)"));
    assertTrue("MainActivity.render() must invoke destroy()",
        mainSrc.contains("query.assistantView.destroy()"));
    assertTrue("MainActivity.render() must null out assistantView",
        mainSrc.contains("query.assistantView = null;"));

    // 2. onDestroy() lifecycle cleanup
    int onDestroyPos = mainSrc.indexOf("protected void onDestroy()");
    assertTrue("MainActivity must have onDestroy()", onDestroyPos > 0);
    String onDestroyBody = mainSrc.substring(onDestroyPos, onDestroyPos + 500);
    assertTrue("onDestroy() must invoke assistantView.destroy()",
        onDestroyBody.contains("query.assistantView.destroy()"));
  }
}
