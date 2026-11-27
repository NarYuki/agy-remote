package moe.n4tsu.agyremote.state

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** Spec 10: light impact on send and jump; selection on page/chip change; success on pairing. */
object Haptics {
    private fun vibrate(context: Context, effect: VibrationEffect) {
        val vm = context.getSystemService(VibratorManager::class.java) ?: return
        runCatching { vm.defaultVibrator.vibrate(effect) }
    }

    private fun primitive(context: Context, id: Int, scale: Float) {
        val vm = context.getSystemService(VibratorManager::class.java) ?: return
        val v = vm.defaultVibrator
        if (v.areAllPrimitivesSupported(id)) {
            vibrate(context, VibrationEffect.startComposition().addPrimitive(id, scale).compose())
        } else {
            vibrate(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    fun light(context: Context) = primitive(context, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f)
    fun press(context: Context) = primitive(context, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
    fun key(context: Context) = primitive(context, VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.3f)
    fun selection(context: Context) = primitive(context, VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
    fun attention(context: Context) = primitive(context, VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f)
    fun success(context: Context) {
        val vm = context.getSystemService(VibratorManager::class.java) ?: return
        val v = vm.defaultVibrator
        if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            vibrate(context, VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 90)
                .compose())
        } else {
            vibrate(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
    }
}
