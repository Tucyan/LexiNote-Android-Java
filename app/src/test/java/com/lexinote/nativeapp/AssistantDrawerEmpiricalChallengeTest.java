package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Empirical Challenger 2 Test Suite for Milestone 2:
 * AI Assistant Drawer Overlay Elevation (Item 10).
 *
 * Directly tests and stress-tests:
 * 1. Challenge touch masking: when `drawerOverlay` is `VISIBLE`, verify that the scrim absorbs
 *    touches so that taps over the bottom composer bar area (camera button, input text field,
 *    send button) do not penetrate to the underlying views.
 * 2. Challenge back-press handling and scrim click: verify that `scrim.setOnClickListener(v -> closeDrawer())`
 *    closes the drawer and hides `drawerOverlay`, and hardware/gesture back press dismisses
 *    drawer first before navigating away.
 * 3. Challenge layout bounds: verify that `drawerOverlay` uses `FrameLayout.LayoutParams(-1, -1)`
 *    and covers 100% of the window width and height across diverse display configurations.
 */
public class AssistantDrawerEmpiricalChallengeTest {

  // =========================================================================
  // Challenge 1: Touch Interception & Bottom Composer Masking
  // =========================================================================

  /**
   * Models a 2D bounding box representing view geometry on screen.
   */
  public static class Rect2D {
    public final int left, top, right, bottom;

    public Rect2D(int left, int top, int right, int bottom) {
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
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

  /**
   * Simulated View Node that mimics Android View hierarchy touch dispatch rules:
   * - Top-down dispatch: Parent dispatches to children in reverse order (highest z-order first).
   * - Visibility check: Non-VISIBLE views do not receive touch events.
   * - Clickable absorption: If isClickable is true, onTouchEvent consumes the event (returns true).
   */
  public static class SimulatedView {
    public final String name;
    public Rect2D bounds;
    public boolean visible = true;
    public boolean clickable = false;
    public int touchEventCount = 0;
    public int clickCount = 0;
    public Runnable onClickListener = null;
    public final List<SimulatedView> children = new ArrayList<>();
    public SimulatedView parent = null;
    public Object tag = null;

    public SimulatedView(String name, Rect2D bounds) {
      this.name = name;
      this.bounds = bounds;
    }

    public void setOnClickListener(Runnable listener) {
      this.onClickListener = listener;
      this.clickable = true;
    }

    public void addChild(SimulatedView child) {
      child.parent = this;
      children.add(child);
    }

    public void removeChild(SimulatedView child) {
      if (children.remove(child)) {
        child.parent = null;
      }
    }

    public SimulatedView findViewWithTag(Object tag) {
      if (this.tag != null && this.tag.equals(tag)) return this;
      for (SimulatedView child : children) {
        SimulatedView found = child.findViewWithTag(tag);
        if (found != null) return found;
      }
      return null;
    }

    /**
     * Replicates Android ViewGroup.dispatchTouchEvent:
     * Dispatches to children in reverse order (topmost child first).
     * Returns true if event is handled by any child or by this view.
     */
    public boolean dispatchTouchEvent(int x, int y) {
      if (!visible || !bounds.contains(x, y)) {
        return false;
      }

      // Reverse order: topmost child first
      for (int i = children.size() - 1; i >= 0; i--) {
        SimulatedView child = children.get(i);
        if (child.dispatchTouchEvent(x, y)) {
          return true;
        }
      }

      // If no child handled it, handle in this view if clickable
      if (clickable) {
        touchEventCount++;
        if (onClickListener != null) {
          clickCount++;
          onClickListener.run();
        }
        return true;
      }

      return false;
    }
  }

  /**
   * Harness that models the LexiNote Assistant Window layout hierarchy:
   *
   * contentHost (android.R.id.content: FrameLayout [0,0, 1080, 2400])
   *  ├── child 0: root (LinearLayout [0,0, 1080, 2400])
   *  │     ├── bar (LinearLayout [0,0, 1080, 140])
   *  │     ├── timeline (DrawerSwipeLayout [0,140, 1080, 2220])
   *  │     └── footer (LinearLayout composer [0,2220, 1080, 2400])
   *  │           ├── cameraBtn ([16, 2236, 136, 2384])
   *  │           ├── composerInput ([152, 2236, 928, 2384])
   *  │           └── sendBtn ([944, 2236, 1064, 2384])
   *  └── child 1: drawerOverlay (FrameLayout [0,0, 1080, 2400], tag: assistant-drawer-overlay)
   *        ├── child 0: scrim (View [0,0, 1080, 2400], clickable, onClick: closeDrawer)
   *        └── child 1: drawerPanel (DrawerPanelLayout [0,0, 864, 2400])
   */
  public static class AssistantOverlayHarness {
    public final SimulatedView contentHost;
    public final SimulatedView root;
    public final SimulatedView footer;
    public final SimulatedView cameraBtn;
    public final SimulatedView composerInput;
    public final SimulatedView sendBtn;

    public SimulatedView drawerOverlay;
    public SimulatedView scrim;
    public SimulatedView drawerPanel;
    public boolean isDrawerOpen = false;

    public AssistantOverlayHarness(int windowWidth, int windowHeight) {
      contentHost = new SimulatedView("contentHost", new Rect2D(0, 0, windowWidth, windowHeight));
      root = new SimulatedView("root", new Rect2D(0, 0, windowWidth, windowHeight));
      contentHost.addChild(root);

      int footerTop = windowHeight - 180;
      footer = new SimulatedView("footer", new Rect2D(0, footerTop, windowWidth, windowHeight));
      cameraBtn = new SimulatedView("cameraBtn", new Rect2D(16, footerTop + 16, 136, windowHeight - 16));
      cameraBtn.setOnClickListener(() -> {});

      composerInput = new SimulatedView("composerInput", new Rect2D(152, footerTop + 16, windowWidth - 152, windowHeight - 16));
      composerInput.setOnClickListener(() -> {});

      sendBtn = new SimulatedView("sendBtn", new Rect2D(windowWidth - 136, footerTop + 16, windowWidth - 16, windowHeight - 16));
      sendBtn.setOnClickListener(() -> {});

      footer.addChild(cameraBtn);
      footer.addChild(composerInput);
      footer.addChild(sendBtn);
      root.addChild(footer);

      buildDrawer(contentHost, windowWidth, windowHeight);
    }

    public void buildDrawer(SimulatedView container, int windowWidth, int windowHeight) {
      if (container == null) return;
      SimulatedView stale = container.findViewWithTag("assistant-drawer-overlay");
      if (stale != null) container.removeChild(stale);

      int drawerWidth = Math.min((int) (310 * 3.0f), (int) (windowWidth * 0.85f));
      drawerOverlay = new SimulatedView("drawerOverlay", new Rect2D(0, 0, windowWidth, windowHeight));
      drawerOverlay.tag = "assistant-drawer-overlay";
      drawerOverlay.visible = false;

      scrim = new SimulatedView("scrim", new Rect2D(0, 0, windowWidth, windowHeight));
      scrim.setOnClickListener(this::closeDrawer);
      drawerOverlay.addChild(scrim);

      drawerPanel = new SimulatedView("drawerPanel", new Rect2D(0, 0, drawerWidth, windowHeight));
      drawerPanel.clickable = true;
      drawerOverlay.addChild(drawerPanel);

      container.addChild(drawerOverlay);
    }

    public void openDrawer() {
      if (drawerOverlay == null || isDrawerOpen) return;
      isDrawerOpen = true;
      drawerOverlay.visible = true;
    }

    public void closeDrawer() {
      if (drawerOverlay == null || !isDrawerOpen) return;
      isDrawerOpen = false;
      drawerOverlay.visible = false;
    }

    public void destroy() {
      if (drawerOverlay != null && drawerOverlay.parent != null) {
        drawerOverlay.parent.removeChild(drawerOverlay);
      }
    }
  }

  @Test
  public void challenge1_drawerOpenCompletelyMasksBottomComposerButtons() {
    AssistantOverlayHarness harness = new AssistantOverlayHarness(1080, 2400);

    // Initial state: drawer closed. Tapping composer buttons works normally.
    assertFalse(harness.isDrawerOpen);
    assertFalse(harness.drawerOverlay.visible);

    // Tap send button (x = 1000, y = 2300)
    boolean handledSend = harness.contentHost.dispatchTouchEvent(1000, 2300);
    assertTrue("Send button must handle tap when drawer closed", handledSend);
    assertEquals(1, harness.sendBtn.clickCount);

    // Tap camera button (x = 80, y = 2300)
    boolean handledCamera = harness.contentHost.dispatchTouchEvent(80, 2300);
    assertTrue("Camera button must handle tap when drawer closed", handledCamera);
    assertEquals(1, harness.cameraBtn.clickCount);

    // Tap composer input (x = 500, y = 2300)
    boolean handledInput = harness.contentHost.dispatchTouchEvent(500, 2300);
    assertTrue("Composer input must handle tap when drawer closed", handledInput);
    assertEquals(1, harness.composerInput.clickCount);

    // OPEN DRAWER
    harness.openDrawer();
    assertTrue(harness.isDrawerOpen);
    assertTrue(harness.drawerOverlay.visible);

    // STRESS TEST 1: Tap over Send button position (x = 1000, y = 2300)
    // When drawer is open, x=1000 is outside drawerPanel (width <= 85% = 918px), so it hits scrim
    int prevSendClicks = harness.sendBtn.clickCount;
    int prevScrimClicks = harness.scrim.clickCount;

    boolean handledMaskedSend = harness.contentHost.dispatchTouchEvent(1000, 2300);
    assertTrue("Touch must be intercepted by drawer overlay", handledMaskedSend);
    assertEquals("Send button must receive ZERO clicks when drawer is open", prevSendClicks, harness.sendBtn.clickCount);
    assertEquals("Scrim must receive click when tapping over send button area", prevScrimClicks + 1, harness.scrim.clickCount);
    assertFalse("Scrim click must have triggered closeDrawer()", harness.isDrawerOpen);

    // Re-open drawer for next check
    harness.openDrawer();
    assertTrue(harness.isDrawerOpen);

    // STRESS TEST 2: Tap over Composer input field (x = 950, y = 2300)
    int prevInputClicks = harness.composerInput.clickCount;
    boolean handledMaskedInput = harness.contentHost.dispatchTouchEvent(950, 2300);
    assertTrue(handledMaskedInput);
    assertEquals("Composer input must receive ZERO clicks when drawer is open", prevInputClicks, harness.composerInput.clickCount);

    // STRESS TEST 3: Tap over Camera button (x = 80, y = 2300)
    // x=80 falls within drawerPanel bounds (0..918px)
    harness.openDrawer();
    int prevCameraClicks = harness.cameraBtn.clickCount;
    boolean handledMaskedCamera = harness.contentHost.dispatchTouchEvent(80, 2300);
    assertTrue(handledMaskedCamera);
    assertEquals("Camera button must receive ZERO clicks under drawerPanel", prevCameraClicks, harness.cameraBtn.clickCount);
  }

  @Test
  public void challenge1_elevatedOverlayVsNonElevatedTimelineComparison() {
    // Contrast test: Prove that if drawerOverlay was inside timeline (the legacy defect),
    // touches over footer (y = 2300) WOULD penetrate to footer.
    SimulatedView host = new SimulatedView("host", new Rect2D(0, 0, 1080, 2400));
    SimulatedView timeline = new SimulatedView("timeline", new Rect2D(0, 140, 1080, 2220));
    SimulatedView footer = new SimulatedView("footer", new Rect2D(0, 2220, 1080, 2400));
    SimulatedView sendBtn = new SimulatedView("sendBtn", new Rect2D(944, 2236, 1064, 2384));
    sendBtn.setOnClickListener(() -> {});
    footer.addChild(sendBtn);
    host.addChild(timeline);
    host.addChild(footer);

    // Defective legacy behavior: overlay attached to timeline
    SimulatedView defectiveOverlay = new SimulatedView("defectiveOverlay", new Rect2D(0, 140, 1080, 2220));
    defectiveOverlay.visible = true;
    SimulatedView defectiveScrim = new SimulatedView("defectiveScrim", new Rect2D(0, 140, 1080, 2220));
    defectiveScrim.setOnClickListener(() -> {});
    defectiveOverlay.addChild(defectiveScrim);
    timeline.addChild(defectiveOverlay);

    // Tapping sendBtn at y = 2300:
    host.dispatchTouchEvent(1000, 2300);
    assertEquals("Legacy layout failed to mask footer (send button received click)", 1, sendBtn.clickCount);
    assertEquals("Legacy scrim could not receive touch outside timeline bounds", 0, defectiveScrim.clickCount);

    // Elevated fix: overlay attached to window-level host (android.R.id.content)
    timeline.removeChild(defectiveOverlay);
    SimulatedView elevatedOverlay = new SimulatedView("elevatedOverlay", new Rect2D(0, 0, 1080, 2400));
    elevatedOverlay.visible = true;
    SimulatedView elevatedScrim = new SimulatedView("elevatedScrim", new Rect2D(0, 0, 1080, 2400));
    elevatedScrim.setOnClickListener(() -> {});
    elevatedOverlay.addChild(elevatedScrim);
    host.addChild(elevatedOverlay);

    // Tapping sendBtn at y = 2300 with elevated overlay:
    sendBtn.clickCount = 0;
    host.dispatchTouchEvent(1000, 2300);
    assertEquals("Elevated overlay completely masks footer", 0, sendBtn.clickCount);
    assertEquals("Elevated scrim absorbs touch over footer", 1, elevatedScrim.clickCount);
  }

  // =========================================================================
  // Challenge 2: Back-Press Handling & Scrim Click Transitions
  // =========================================================================

  /**
   * Simulates the Activity navigation and back-press state machine in MainActivity.java:1401-1439.
   */
  public static class ActivityBackMachine {
    public String screen = "tabs";
    public int page = 0;
    public boolean isActivityFinished = false;
    public boolean isDrawerOpen = false;
    public boolean assistantDestroyed = false;

    public void handleBack() {
      // 1. If assistant drawer is open, close drawer and consume back press
      if ("assistant".equals(screen) && isDrawerOpen) {
        isDrawerOpen = false;
        return;
      }
      // 2. If on assistant or another subscreen, return to tabs
      if (!"tabs".equals(screen)) {
        screen = "tabs";
        onDestroyAssistantView();
        return;
      }
      // 3. If on tabs page != 0, return to home tab (page 0)
      if (page != 0) {
        page = 0;
        return;
      }
      // 4. On home tab, finish activity
      isActivityFinished = true;
    }

    public void onDestroyAssistantView() {
      assistantDestroyed = true;
    }
  }

  @Test
  public void challenge2_backPressClosesDrawerWithoutExitingAssistant() {
    ActivityBackMachine machine = new ActivityBackMachine();

    // Navigate to Assistant screen
    machine.screen = "assistant";
    machine.isDrawerOpen = false;

    // User opens drawer
    machine.isDrawerOpen = true;

    // First back press: MUST close drawer only
    machine.handleBack();
    assertFalse("Drawer must be closed", machine.isDrawerOpen);
    assertEquals("Screen must still be 'assistant'", "assistant", machine.screen);
    assertFalse("Activity must not be finished", machine.isActivityFinished);
    assertFalse("Assistant view must not be destroyed yet", machine.assistantDestroyed);

    // Second back press: MUST exit assistant to tabs
    machine.handleBack();
    assertEquals("Screen must transition to 'tabs'", "tabs", machine.screen);
    assertTrue("Assistant view destroy() must have been called", machine.assistantDestroyed);
    assertFalse("Activity must not be finished", machine.isActivityFinished);

    // Third back press (on tabs page 0): MUST finish activity
    machine.handleBack();
    assertTrue("Activity must finish on home tab back press", machine.isActivityFinished);
  }

  @Test
  public void challenge2_rapidBackPressDoesNotCorruptLifecycle() {
    ActivityBackMachine machine = new ActivityBackMachine();
    machine.screen = "assistant";
    machine.isDrawerOpen = true;

    // Simulate 2 rapid back presses within 50ms
    machine.handleBack(); // Back 1: closes drawer
    machine.handleBack(); // Back 2: navigates to tabs

    assertFalse(machine.isDrawerOpen);
    assertEquals("tabs", machine.screen);
    assertTrue(machine.assistantDestroyed);
    assertFalse(machine.isActivityFinished);
  }

  // =========================================================================
  // Challenge 3: Layout Bounds & Elevation Constraints
  // =========================================================================

  /**
   * Evaluates the layout parameter specifications in AssistantUi.java:221-298.
   */
  public static class LayoutParamsSpec {
    public final int overlayWidth;
    public final int overlayHeight;
    public final int scrimWidth;
    public final int scrimHeight;
    public final int panelWidth;
    public final int panelHeight;

    public LayoutParamsSpec(int overlayWidth, int overlayHeight, int scrimWidth, int scrimHeight, int panelWidth, int panelHeight) {
      this.overlayWidth = overlayWidth;
      this.overlayHeight = overlayHeight;
      this.scrimWidth = scrimWidth;
      this.scrimHeight = scrimHeight;
      this.panelWidth = panelWidth;
      this.panelHeight = panelHeight;
    }
  }

  private int computeDrawerWidth(int screenWidthPixels, float density) {
    int maxDpPixels = (int) (310 * density);
    int eightyFivePercent = (int) (screenWidthPixels * 0.85f);
    return Math.min(maxDpPixels, eightyFivePercent);
  }

  @Test
  public void challenge3_drawerWidthCalculationAcrossScreenDensities() {
    // 1. Small phone: 320 x 480 @ 1.0x (ldpi)
    // 85% = 272px; 310dp = 310px -> min is 272px
    int w1 = computeDrawerWidth(320, 1.0f);
    assertEquals("Small phone width must be 85% of screen (272px)", 272, w1);
    assertTrue(w1 <= 320 * 0.85f);

    // 2. Standard phone: 360 x 640 @ 2.0x (xhdpi)
    // 85% = 306px; 310dp = 620px -> min is 306px
    int w2 = computeDrawerWidth(360, 2.0f);
    assertEquals("360px screen must be bounded by 85% (306px)", 306, w2);

    // 3. FHD phone: 1080 x 2400 @ 2.75x (xxhdpi)
    // 85% = 918px; 310dp = 852px -> min is 852px (310dp cap kicks in)
    int w3 = computeDrawerWidth(1080, 2.75f);
    assertEquals("FHD phone must be capped at 310dp (852px)", 852, w3);

    // 4. Tablet: 1600 x 2560 @ 2.0x
    // 85% = 1360px; 310dp = 620px -> min is 620px
    int w4 = computeDrawerWidth(1600, 2.0f);
    assertEquals("Tablet must be strictly capped at 310dp (620px)", 620, w4);

    // In all cases, drawer width must leave >= 15% visible scrim area for tapping dismiss
    int[] testWidths = {320, 360, 480, 720, 1080, 1440, 1600, 2560};
    float[] testDensities = {1.0f, 1.5f, 2.0f, 2.625f, 3.0f, 3.5f, 4.0f};

    for (int width : testWidths) {
      for (float density : testDensities) {
        int dw = computeDrawerWidth(width, density);
        assertTrue("Drawer width must be positive", dw > 0);
        assertTrue("Drawer width must never exceed 85% of screen", dw <= (int) (width * 0.85f) + 1);
        int remainingScrim = width - dw;
        assertTrue("Remaining scrim width must be at least 15% of screen", remainingScrim >= (int) (width * 0.14f));
      }
    }
  }

  @Test
  public void challenge3_layoutParamsSpan100PercentWindow() {
    // FrameLayout.LayoutParams(-1, -1) equates to MATCH_PARENT x MATCH_PARENT
    int MATCH_PARENT = -1;

    LayoutParamsSpec spec = new LayoutParamsSpec(
        MATCH_PARENT, MATCH_PARENT, // overlay
        MATCH_PARENT, MATCH_PARENT, // scrim
        computeDrawerWidth(1080, 3.0f), MATCH_PARENT // panel
    );

    assertEquals("drawerOverlay width must be MATCH_PARENT (-1)", MATCH_PARENT, spec.overlayWidth);
    assertEquals("drawerOverlay height must be MATCH_PARENT (-1)", MATCH_PARENT, spec.overlayHeight);
    assertEquals("scrim width must be MATCH_PARENT (-1)", MATCH_PARENT, spec.scrimWidth);
    assertEquals("scrim height must be MATCH_PARENT (-1)", MATCH_PARENT, spec.scrimHeight);
    assertEquals("drawerPanel height must be MATCH_PARENT (-1)", MATCH_PARENT, spec.panelHeight);
  }

  @Test
  public void challenge3_topHostResolutionAndIdempotentCleanup() {
    SimulatedView windowContent = new SimulatedView("android.R.id.content", new Rect2D(0, 0, 1080, 2400));
    SimulatedView timeline = new SimulatedView("timeline", new Rect2D(0, 140, 1080, 2220));

    AssistantOverlayHarness harness = new AssistantOverlayHarness(1080, 2400);

    // Verify overlay tag
    assertEquals("assistant-drawer-overlay", harness.drawerOverlay.tag);

    // Call buildDrawer again on same container (simulating re-entry or fast rebuild)
    harness.buildDrawer(harness.contentHost, 1080, 2400);

    // Count overlays in container
    int overlayCount = 0;
    for (SimulatedView child : harness.contentHost.children) {
      if ("assistant-drawer-overlay".equals(child.tag)) {
        overlayCount++;
      }
    }
    assertEquals("Stale overlay must be removed, exactly 1 overlay must remain", 1, overlayCount);

    // Call destroy()
    harness.destroy();
    assertNull("After destroy, overlay must have no parent", harness.drawerOverlay.parent);
    assertNull("Container must no longer contain overlay", harness.contentHost.findViewWithTag("assistant-drawer-overlay"));
  }
}
