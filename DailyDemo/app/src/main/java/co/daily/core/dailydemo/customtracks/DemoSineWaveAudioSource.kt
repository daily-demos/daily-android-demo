package co.daily.core.dailydemo.customtracks

import android.os.SystemClock
import android.util.Log
import co.daily.model.customtrack.AudioFrameFormat
import co.daily.model.customtrack.CustomAudioFrameConsumer
import co.daily.model.customtrack.CustomAudioSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.sin

class DemoSineWaveAudioSource(private val sineFreqHz: Int) : CustomAudioSource() {

    companion object {
        private const val TAG = "DemoSineWaveAudioSource"
    }

    private val activeConsumer = AtomicReference<CustomAudioFrameConsumer?>(null)

    override fun attachFrameConsumer(consumer: CustomAudioFrameConsumer) {

        Log.i(TAG, "Frame consumer attached")

        activeConsumer.set(consumer)

        thread(priority = Thread.MAX_PRIORITY) {
            val format = AudioFrameFormat(
                bitsPerSample = 16,
                sampleRateHz = 48000,
                channelCount = 1
            )

            val periodMs = 50

            val data = ShortArray((format.sampleRateHz * periodMs) / 1000)
            var samplesRendered = 0

            var targetTime = SystemClock.uptimeMillis() + periodMs

            while (true) {

                while (true) {

                    val now = SystemClock.uptimeMillis()

                    if (targetTime <= now) {
                        break
                    }

                    Thread.sleep(targetTime - now)
                }

                targetTime += periodMs

                for (i in data.indices) {
                    val sampleNum = samplesRendered + i

                    val timeElapsedSecs = sampleNum.toDouble() / format.sampleRateHz.toDouble()

                    data[i] = (sin(timeElapsedSecs * 2.0 * Math.PI * sineFreqHz) * Short.MAX_VALUE.toDouble()).roundToInt()
                        .toShort()
                }

                samplesRendered += data.size

                if (activeConsumer.get() != consumer) {
                    Log.i(TAG, "Shutting down sample audio renderer")
                    return@thread
                }

                consumer.sendFrame(format, data)
            }
        }
    }

    override fun detachFrameConsumer() {
        Log.i(TAG, "Frame consumer detached")
        activeConsumer.set(null)
    }
}
