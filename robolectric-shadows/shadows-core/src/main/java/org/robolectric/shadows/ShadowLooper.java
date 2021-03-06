package org.robolectric.shadows;

import android.os.Looper;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.annotation.HiddenApi;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.Scheduler;
import org.robolectric.util.SoftThreadLocal;

import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

/**
 * Shadow for {@link android.os.Looper} that enqueues posted {@link Runnable}s to be run
 * (on this thread) later. {@code Runnable}s that are scheduled to run immediately can be
 * triggered by calling {@link #idle()}.
 *
 * @see ShadowMessageQueue
 */
@Implements(Looper.class)
public class ShadowLooper {
  private static final Thread MAIN_THREAD = Thread.currentThread();
  private static SoftThreadLocal<Looper> looperForThread = makeThreadLocalLoopers();
  private Scheduler scheduler = new Scheduler();
  private Thread myThread = Thread.currentThread();
  private @RealObject Looper realObject;

  boolean quit;

  private static SoftThreadLocal<Looper> makeThreadLocalLoopers() {
    return new SoftThreadLocal<Looper>() {
      @Override protected Looper create() {
        return createLooper();
      }
    };
  }

  private static Looper createLooper() {
    return ReflectionHelpers.callConstructor(Looper.class, from(boolean.class, Thread.currentThread() != MAIN_THREAD));
  }

  @Resetter
  public static synchronized void resetThreadLoopers() {
    // Blech. We need to share the main looper because somebody might refer to it in a static
    // field. We also need to keep it in a soft reference so we don't max out permgen.

    if (Thread.currentThread() != MAIN_THREAD) {
      throw new RuntimeException("you should only be calling this from the main thread!");
    }

    Looper mainLooper = looperForThread.get();
    looperForThread = makeThreadLocalLoopers();
    looperForThread.set(mainLooper);
    shadowOf(mainLooper).reset();
  }

  @Implementation
  public static Looper getMainLooper() {
    ShadowApplication shadowApplication = ShadowApplication.getInstance();
    if ((shadowApplication == null) && (Thread.currentThread() == MAIN_THREAD)) {
      Looper mainLooper = looperForThread.get();
      return mainLooper;
    } else {
      // might still throw NullPointerException
      // better than returning null because this fails early.
      return shadowApplication.getMainLooper();
    }
  }

  @Implementation
  public static void loop() {
    shadowOf(myLooper()).doLoop();
  }

  @Implementation
  public static synchronized Looper myLooper() {
    return looperForThread.get();
  }

  private void doLoop() {
    if (this != shadowOf(getMainLooper())) {
      synchronized (realObject) {
        while (!quit) {
          try {
            realObject.wait();
          } catch (InterruptedException ignore) {
          }
        }
      }
    }
  }

  @Implementation
  public void quit() {
    if (this == shadowOf(getMainLooper())) throw new RuntimeException("Main thread not allowed to quit");
    synchronized (realObject) {
      quit = true;
      scheduler.reset();
      realObject.notifyAll();
    }
  }

  @Implementation
  public Thread getThread() {
    return myThread;
  }

  @HiddenApi @Implementation
  public int postSyncBarrier() {
    return 1;
  }

  @HiddenApi @Implementation
  public void removeSyncBarrier(int token) {
  }

  public boolean hasQuit() {
    synchronized (realObject) {
      return quit;
    }
  }

  public static void pauseLooper(Looper looper) {
    shadowOf(looper).pause();
  }

  public static void unPauseLooper(Looper looper) {
    shadowOf(looper).unPause();
  }

  public static void pauseMainLooper() {
    pauseLooper(Looper.getMainLooper());
  }

  public static void unPauseMainLooper() {
    unPauseLooper(Looper.getMainLooper());
  }

  public static void idleMainLooper() {
    shadowOf(Looper.getMainLooper()).idle();
  }

  public static void idleMainLooper(long interval) {
    shadowOf(Looper.getMainLooper()).idle(interval);
  }

  public static void idleMainLooperConstantly(boolean shouldIdleConstantly) {
    shadowOf(Looper.getMainLooper()).idleConstantly(shouldIdleConstantly);
  }

  public static void runMainLooperOneTask() {
    shadowOf(Looper.getMainLooper()).runOneTask();
  }

  public static void runMainLooperToNextTask() {
    shadowOf(Looper.getMainLooper()).runToNextTask();
  }
    
  /**
   * Runs any immediately runnable tasks previously queued on the UI thread,
   * e.g. by {@link android.app.Activity#runOnUiThread(Runnable)} or {@link android.os.AsyncTask#onPostExecute(Object)}.
   *
   * <p>Note: calling this method does not pause or un-pause the scheduler.</p>
   */
  public static void runUiThreadTasks() {
    ShadowApplication.getInstance().getForegroundThreadScheduler().advanceBy(0);
  }

  public static void runUiThreadTasksIncludingDelayedTasks() {
    ShadowApplication.getInstance().getForegroundThreadScheduler().advanceToLastPostedRunnable();
  }

  /**
   * Causes {@link Runnable}s that have been scheduled to run immediately to actually run. Does not advance the
   * scheduler's clock;
   */
  public void idle() {
    scheduler.advanceBy(0);
  }

  /**
   * Causes {@link Runnable}s that have been scheduled to run within the next {@code intervalMillis} milliseconds to
   * run while advancing the scheduler's clock.
   *
   * @param intervalMillis milliseconds to advance
   */
  public void idle(long intervalMillis) {
    scheduler.advanceBy(intervalMillis);
  }

  public void idleConstantly(boolean shouldIdleConstantly) {
    scheduler.idleConstantly(shouldIdleConstantly);
  }

  /**
   * Causes all of the {@link Runnable}s that have been scheduled to run while advancing the scheduler's clock to the
   * start time of the last scheduled {@link Runnable}.
   */
  public void runToEndOfTasks() {
    scheduler.advanceToLastPostedRunnable();
  }

  /**
   * Causes the next {@link Runnable}(s) that have been scheduled to run while advancing the scheduler's clock to its
   * start time. If more than one {@link Runnable} is scheduled to run at this time then they will all be run.
   */
  public void runToNextTask() {
    scheduler.advanceToNextPostedRunnable();
  }

  /**
   * Causes only one of the next {@link Runnable}s that have been scheduled to run while advancing the scheduler's
   * clock to its start time. Only one {@link Runnable} will run even if more than one has ben scheduled to run at the
   * same time.
   */
  public void runOneTask() {
    scheduler.runOneTask();
  }

  /**
   * Enqueue a task to be run later.
   *
   * @param runnable    the task to be run
   * @param delayMillis how many milliseconds into the (virtual) future to run it
   * @return true if the runnable is enqueued
   * @see android.os.Handler#postDelayed(Runnable,long)
   * @deprecated Use a {@link android.os.Handler} instance to post to a looper.
   */
  @Deprecated
  public boolean post(Runnable runnable, long delayMillis) {
    if (!quit) {
      scheduler.postDelayed(runnable, delayMillis);
      return true;
    } else {
      return false;
    }
  }
  
  /**
   * Enqueue a task to be run ahead of all other delayed tasks.
   *
   * @param runnable    the task to be run
   * @return true if the runnable is enqueued
   * @see android.os.Handler#postAtFrontOfQueue(Runnable)
   * @deprecated Use a {@link android.os.Handler} instance to post to a looper.
   */
  @Deprecated
  public boolean postAtFrontOfQueue(Runnable runnable) {
    if (!quit) {
      scheduler.postAtFrontOfQueue(runnable);
      return true;
    } else {
      return false;
    }
  }

  public void pause() {
    scheduler.pause();
  }

  public void unPause() {
    scheduler.unPause();
  }

  public boolean isPaused() {
    return scheduler.isPaused();
  }

  public boolean setPaused(boolean shouldPause) {
    boolean wasPaused = isPaused();
    if (shouldPause) {
      pause();
    } else {
      unPause();
    }
    return wasPaused;
  }

  /**
   * Causes all enqueued tasks to be discarded, and pause state to be reset
   */
  public void reset() {
    scheduler = new Scheduler();
    shadowOf(realObject.getQueue()).reset();
    quit = false;
  }

  /**
   * Returns the {@link org.robolectric.util.Scheduler} that is being used to manage the enqueued tasks.
   *
   * @return the {@link org.robolectric.util.Scheduler} that is being used to manage the enqueued tasks.
   */
  public Scheduler getScheduler() {
    return scheduler;
  }

  public void runPaused(Runnable r) {
    boolean wasPaused = setPaused(true);
    try {
      r.run();
    } finally {
      if (!wasPaused) unPause();
    }
  }
}
