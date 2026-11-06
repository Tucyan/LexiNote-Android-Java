package com.lexinote.nativeapp;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReviewSessionStateTest {
  @Test public void failedRatingKeepsPayloadAndLocksConcurrentSubmission() {
    ReviewSessionState s = new ReviewSessionState();
    s.resume(100);
    ReviewSessionState.Rating first = s.begin("p", "v", "简单", "event", 1000, 250);
    assertNotNull(first);
    assertNull(s.begin("p", "v", "简单", "another", 1100, 260));
    s.fail();
    ReviewSessionState.Rating retry = s.begin("p", "v", "简单", "different", 2000, 500);
    assertSame(first, retry);
    assertEquals(150, retry.durationMillis);
    assertEquals(1000, retry.occurredAt);
  }
  @Test public void timerCountsOnlyVisibleTimeAndResetsAfterSuccess() {
    ReviewSessionState s = new ReviewSessionState();
    s.resume(100); s.pause(200); s.resume(1000);
    ReviewSessionState.Rating first = s.begin("p", "v", "困难", "e", 2000, 1050);
    assertEquals(150, first.durationMillis);
    s.succeed(1100);
    ReviewSessionState.Rating next = s.begin("p", "w", "再次", "f", 3000, 1150);
    assertEquals(50, next.durationMillis);
  }
  @Test public void restoredPendingRatingRemainsLockedUntilEventIsChecked() {
    ReviewSessionState s = new ReviewSessionState();
    ReviewSessionState.Rating pending = new ReviewSessionState.Rating("p", "v", "easy", "e", 1000, 50);
    s.restorePending(pending);
    assertNull(s.begin("p", "v", "easy", "other", 2000, 100));
    s.fail();
    assertNull(s.begin("p", "v", "hard", "other", 2000, 100));
    assertSame(pending, s.begin("p", "v", "easy", "other", 2000, 100));
  }
  @Test public void recoveryRecheckLocksAndRetainsOriginalPayload() {
    ReviewSessionState s = new ReviewSessionState();
    ReviewSessionState.Rating r = new ReviewSessionState.Rating("plan", "word", "easy", "fixed", 10, 20);
    s.restorePending(r); s.fail();
    assertTrue(s.startRecoveryCheck("plan", "word", "easy"));
    assertFalse(s.startRecoveryCheck("plan", "word", "easy"));
    assertSame(r, s.pending());
  }
}
