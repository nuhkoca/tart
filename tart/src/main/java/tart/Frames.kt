package tart

import android.os.Build
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import tart.internal.enforceMainThread
import tart.internal.isOnMainThread
import tart.internal.lastFrameTimeNanos
import tart.internal.mainHandler
import tart.internal.onNextFrameMetrics
import tart.internal.postAtFrontOfQueueAsync
import java.util.concurrent.TimeUnit.NANOSECONDS

// TODO Not sure if we should expose this. postFrameCallback() isn't tied to any
// particular window, so this window might not actually render if there's no reason to re render.
// If that happens the next frame metrics will be much later.
// We probably need to break up "frozen touch" and "frozen frame" into 2 different things.
internal fun Window.onNextFrameDisplayed(callback: (CpuDuration) -> Unit) {
  if (isChoreographerDoingFrame()) {
    val frameTimeNanos = Choreographer.getInstance().lastFrameTimeNanos
    onCurrentFrameDisplayed(frameTimeNanos, callback)
  } else {
    Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
      onCurrentFrameDisplayed(frameTimeNanos, callback)
    }
  }
}

// TODO Should this be on an object instead?
fun isChoreographerDoingFrame(): Boolean {
  if (!isOnMainThread()) {
    return false
  }
  val stackTrace = RuntimeException().stackTrace
  for (i in stackTrace.lastIndex downTo 0) {
    val element = stackTrace[i]
    if (element.className == "android.view.Choreographer" &&
      element.methodName == "doFrame"
    ) {
      return true
    }
  }
  return false
}

fun Window.onCurrentFrameDisplayed(
  frameTimeNanos: Long,
  callback: (CpuDuration) -> Unit,
) {
  enforceMainThread()
  if (Build.VERSION.SDK_INT >= 26) {
    lateinit var frameSyncQueuedIfMissedFrameMetrics: CpuDuration
    mainHandler.postAtFrontOfQueueAsync {
      frameSyncQueuedIfMissedFrameMetrics = CpuDuration.now()
    }
    onNextFrameMetrics { frameMetrics ->
      val metricsFrameTimeNanos = frameMetrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
      val bufferSwap = if (metricsFrameTimeNanos == frameTimeNanos) {
        val intendedVsync = frameMetrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        // TOTAL_DURATION is the duration from the intended vsync
        // time, not the actual vsync time.
        val frameDuration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val bufferSwapUptimeNanos = intendedVsync + frameDuration
        CpuDuration.deriveRealtimeFromUptime(NANOSECONDS, bufferSwapUptimeNanos)
      } else {
        null
      }
      mainHandler.post {
        // Note: frameSyncQueuedIfMissedFrameMetrics is guaranteed to be set here
        // as this main thread post was enqueued AFTER the main thread post at front.
        callback(bufferSwap ?: frameSyncQueuedIfMissedFrameMetrics)
      }
    }
  } else {
    mainHandler.postAtFrontOfQueueAsync {
      callback(CpuDuration.now())
    }
  }
}
