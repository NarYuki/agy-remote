/*
 * Copyright 2025 Kyant (https://github.com/Kyant0/AndroidLiquidGlass)
 * Licensed under the Apache License, Version 2.0; see android/third_party/AndroidLiquidGlass/LICENSE.
 * From the Backdrop Catalog at tag 2.0.1 (65ab177e90e5c1d8c62e70cf7755841982da65f6),
 * Modified for AGY Remote: the expect declaration is replaced by its Android implementation (one Choreographer frame).
 */
package moe.n4tsu.agyremote.ui.components.liquid

import androidx.compose.runtime.withFrameNanos

suspend fun awaitFrame() {
    withFrameNanos { }
}
