package io.nekohasekai.sagernet.group

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Execute the same actual layout/text-centre assertions with real native fonts at both APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagR3NativeGeometryTest : TagR3GeometryTest()
