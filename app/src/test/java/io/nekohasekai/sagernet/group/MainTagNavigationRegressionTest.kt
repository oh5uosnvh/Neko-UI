package io.nekohasekai.sagernet.group

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.appcompat.widget.AppCompatImageButton
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.ui.CompactNavigationMenu
import io.nekohasekai.sagernet.ui.ConfigurationControlOverlay
import io.nekohasekai.sagernet.ui.GroupFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.NavigationFrameGate
import io.nekohasekai.sagernet.ui.PageSlideTransition
import io.nekohasekai.sagernet.ui.TagGroupFragment
import io.nekohasekai.sagernet.ui.TagGroupNavigation
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.ToolbarFragment
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.time.Duration

/**
 * Real, final MainActivity, real FragmentManager, GroupFragment and TagGroupFragment.
 * Only Main's application/service bootstrap is skipped: invoke its parent's lifecycle and
 * install the same binding/menu/overlay/lifecycle callbacks that onCreate normally installs.
 * No navigation method, fragment callback, animator or content gate is replaced by a fake.
 * The device-back test exercises TagGroupFragment's actual lifecycle-owned dispatcher callback,
 * NOT the unrelated configuration/home fallback normally registered by Main.onCreate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF) // Pure UI: avoid host TLS JNI on musl/aarch64.
class MainTagNavigationRegressionTest {
    @Before fun pauseAnimationClock() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
    }

    @After fun restoreAnimationClock() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
    }

    private class Fixture : AutoCloseable {
        // Existing test-only fixture injects isolated Room stores and attaches SagerNet's context,
        // without SagerNet.onCreate/Libcore.initCore or any real VPN/service connection.
        val environment = QuickPopupTestEnvironment()
        private val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        private val canvas = Canvas()
        val root: GroupFragment
        private val slide: PageSlideTransition get() = ReflectionHelpers.getField(activity, "pageSlide")
        val current: ToolbarFragment? get() = ReflectionHelpers.getField(activity, "currentMainFragment")
        val navId: Int get() = ReflectionHelpers.getField(activity, "currentNavId")
        val cache: Map<Int, ToolbarFragment> get() = ReflectionHelpers.getField(activity, "fragmentCache")

        /** invokespecial: reflection Method.invoke would dispatch back into Main's override. */
        private fun parentLifecycle(name: String, savedState: Boolean = false) {
            val type = if (savedState) MethodType.methodType(Void.TYPE, Bundle::class.java)
                else MethodType.methodType(Void.TYPE)
            val method = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findSpecial(ThemedActivity::class.java, name, type, MainActivity::class.java)
                .bindTo(activity)
            if (savedState) method.invokeWithArguments(null as Bundle?) else method.invokeWithArguments()
        }

        init {
            activity.setTheme(R.style.Theme_SagerNet_Blue)
            parentLifecycle("onCreate", savedState = true)
            activity.binding = LayoutMainBinding.inflate(activity.layoutInflater)
            ReflectionHelpers.setField(activity, "navigation",
                CompactNavigationMenu(activity, activity.binding.root) { activity.onNavigationItemSelected(it) })
            ReflectionHelpers.setField(activity, "controlOverlay",
                ConfigurationControlOverlay(activity.binding.fragmentHolder, activity.binding.controlBar))
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                ReflectionHelpers.getField<FragmentManager.FragmentLifecycleCallbacks>(activity, "navInsetCallbacks"), true)
            activity.setContentView(activity.binding.root)
            parentLifecycle("onStart")
            parentLifecycle("onResume")
            parentLifecycle("onPostResume")
            // ActivityController.setup cannot be used: it would call Main.onCreate and bind VPN.
            // ReportFragment's platform lifecycle dispatch is also bypassed by invokespecial.
            (activity.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
            controller.visible()
            frames(2)
            assertTrue(activity.binding.root.isAttachedToWindow)
            assertTrue(activity.displayFragmentWithId(R.id.nav_group))
            awaitView { it is GroupFragment }
            root = current as GroupFragment
            awaitIo { root.groupAdapter.itemCount > 0 }
            frames(3)
            assertSame(root, cache[R.id.nav_group])
            assertEquals(Lifecycle.State.RESUMED, root.viewLifecycleOwner.lifecycle.currentState)
            assertEquals(0f, root.requireView().translationX, 0f)
        }

        fun frames(count: Int = 1) {
            repeat(count) {
                val before = SystemClock.uptimeMillis()
                activity.binding.root.postInvalidateOnAnimation()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                // LEGACY has no surface: draw clears dirty flags, while ViewRootImpl alone
                // delivers the genuine pre-draw traversal. Never dispatch synthetic pre-draw.
                activity.binding.root.draw(canvas)
                assertEquals(16L, SystemClock.uptimeMillis() - before)
            }
        }

        private fun awaitView(predicate: (ToolbarFragment) -> Boolean) {
            repeat(16) {
                if (current?.let { predicate(it) && it.view != null } == true) return
                frames()
            }
            fail("Navigation never committed the requested real fragment; current=$current navId=$navId")
        }

        fun awaitIo(ready: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!ready() && System.nanoTime() < deadline) {
                // Drain only already-due main work while Room runs on IO. Advancing hundreds of
                // virtual frames here could trip the 2s safety valve before real IO gets CPU time.
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
            assertTrue("The real in-memory Room loader did not finish", ready())
        }

        fun openTags(): TagGroupFragment {
            TagGroupNavigation.open(activity)
            awaitView { it is TagGroupFragment }
            return current as TagGroupFragment
        }

        fun openMembers(tagId: Long): GroupFragment {
            activity.displayFragment(GroupFragment().apply {
                arguments = Bundle().apply { putLong("tag_id", tagId); putBoolean("tag_edit", true) }
            })
            awaitView { it is GroupFragment && it !== root }
            return current as GroupFragment
        }

        fun awaitTags() { awaitView { it is TagGroupFragment } }

        fun assertMotion(outgoing: View, incoming: View, forward: Boolean) {
            val width = activity.binding.fragmentHolder.width.toFloat()
            assertTrue("Page host must be laid out", width > 0f)
            assertTrue("FragmentManager must create Main's paired page animators", slide.isRunning)
            assertEquals(if (forward) width else -width, incoming.translationX, .01f)
            assertEquals(0f, outgoing.translationX, .01f)
            repeat(12) {
                if (if (forward) outgoing.translationX < 0f else outgoing.translationX > 0f) {
                    assertTrue(if (forward) incoming.translationX in .001f..(width - .001f)
                        else incoming.translationX in (-width + .001f)..-.001f)
                    assertEquals(1f, incoming.alpha, 0f)
                    assertEquals(1f, outgoing.alpha, 0f)
                    val running = ReflectionHelpers.getField<Map<ValueAnimator, *>>(slide, "running")
                    assertEquals("Both FM effects share the same gate", 2, running.size)
                    val animators = running.keys.toList()
                    assertTrue(animators.all { animator -> animator.isStarted })
                    assertEquals(animators[0].currentPlayTime, animators[1].currentPlayTime)
                    return
                }
                frames()
            }
            fail("Ready tag navigation never started before ${NavigationFrameGate.MAX_WAIT_MS}ms timeout")
        }

        fun settle() {
            frames(24)
            assertFalse(slide.isRunning)
            assertEquals(0f, current!!.requireView().translationX, 0f)
            assertEquals(1f, current!!.requireView().alpha, 0f)
        }

        fun awaitRoot() {
            awaitView { it === root }
            assertSame("nav_group must resolve the cached root, not the tag page with the same navId",
                root, current)
            assertSame(root, cache[R.id.nav_group])
            assertEquals(R.id.nav_group, navId)
        }

        override fun close() {
            ReflectionHelpers.callInstanceMethod<Void>(activity, "cancelNavigationWork")
            parentLifecycle("onPause")
            (activity.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED
            parentLifecycle("onStop")
            parentLifecycle("onDestroy")
            (activity.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
            shadowOf(Looper.getMainLooper()).idle()
            environment.close()
        }
    }

    private fun tagBackButton(fragment: TagGroupFragment): AppCompatImageButton {
        val toolbar = fragment.toolbar
        return (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
            .filterIsInstance<AppCompatImageButton>()
            .single { it.contentDescription == fragment.getString(R.string.tag_back) }
    }

    @Test fun toolbarBackLeavesSameNavIdTagPageAndReusesCachedRootWithBackwardMotion() {
        Fixture().use { f ->
            val oldRootView = f.root.requireView()
            val tags = f.openTags()
            val tagView = tags.requireView()
            f.awaitIo { tagView.findViewById<View>(R.id.tag_empty).visibility == View.VISIBLE }
            assertEquals(R.id.nav_group, f.navId)
            assertNotSame(f.cache[R.id.nav_group], tags)
            f.assertMotion(oldRootView, tagView, forward = true)
            f.settle()
            assertTrue("Click the actual toolbar back ImageButton, not returnToGroups by reflection",
                tagBackButton(tags).performClick())
            f.awaitRoot()
            assertSame(oldRootView, f.root.requireView())
            f.assertMotion(tagView, oldRootView, forward = false)
            f.settle()
            assertFalse(tags.isAdded)
            assertFalse(f.root.isHidden)
        }
    }

    @Test fun deviceBackDispatchesRealLifecycleCallbackAndDoesNotStayOnTagPage() {
        Fixture().use { f ->
            val tags = f.openTags()
            val tagView = tags.requireView()
            f.awaitIo { tagView.findViewById<View>(R.id.tag_empty).visibility == View.VISIBLE }
            f.assertMotion(f.root.requireView(), tagView, forward = true)
            f.settle()
            assertTrue(f.activity.onBackPressedDispatcher.hasEnabledCallbacks())
            // This is the AndroidX entry point used by device back; no test callback is added.
            f.activity.onBackPressedDispatcher.onBackPressed()
            f.awaitRoot()
            assertFalse("Back to the group root is navigation, not Activity.finish", f.activity.isFinishing)
            f.assertMotion(tagView, f.root.requireView(), forward = false)
            f.settle()
            assertFalse(tags.isAdded)
        }
    }

    @Test fun emptyTagColdLoadReleasesActualMainGateBeforeTimeoutAndReopenReusesInstance() {
        Fixture().use { f ->
            val tags = f.openTags()
            val firstView = tags.requireView()
            val startedAt = SystemClock.uptimeMillis()
            f.awaitIo { firstView.findViewById<View>(R.id.tag_empty).visibility == View.VISIBLE }
            assertEquals(0, firstView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tag_list)
                .adapter!!.itemCount)
            f.assertMotion(f.root.requireView(), firstView, forward = true)
            assertTrue(SystemClock.uptimeMillis() - startedAt < NavigationFrameGate.MAX_WAIT_MS)
            f.settle()
            f.activity.onBackPressedDispatcher.onBackPressed()
            f.awaitRoot()
            f.assertMotion(firstView, f.root.requireView(), forward = false)
            f.settle()
            val reopened = f.openTags()
            assertSame("TagGroupNavigation's weak cache must reuse the same live fragment", tags, reopened)
            assertNotSame("Removed tag page recreates its view; instance cache is not a stale View cache",
                firstView, reopened.requireView())
            f.awaitIo { reopened.requireView().findViewById<View>(R.id.tag_empty).visibility == View.VISIBLE }
            f.assertMotion(f.root.requireView(), reopened.requireView(), forward = true)
            f.settle()
            assertEquals(1, f.activity.supportFragmentManager.fragments.filterIsInstance<TagGroupFragment>().size)
            assertSame(f.root, f.cache[R.id.nav_group])
        }
    }

    @Test fun realNonemptyTagAdapterReleasesMainGateWithoutAnEmptyMarker() {
        Fixture().use { f ->
            io.nekohasekai.sagernet.database.TagGroupStore.create("Navigation regression tag")
            val tags = f.openTags()
            val view = tags.requireView()
            val list = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tag_list)
            f.awaitIo { list.adapter!!.itemCount == 1 }
            assertEquals(View.GONE, view.findViewById<View>(R.id.tag_empty).visibility)
            f.assertMotion(f.root.requireView(), view, forward = true)
            f.settle()
            assertTrue(tagBackButton(tags).performClick())
            f.awaitRoot()
            f.assertMotion(view, f.root.requireView(), forward = false)
            f.settle()
        }
    }

    @Test fun memberBackReturnsTagListWithBackwardMotionAndAddUsesOriginalGroupIntent() {
        Fixture().use { f ->
            val tag = io.nekohasekai.sagernet.database.TagGroupStore.create("Flower")
            io.nekohasekai.sagernet.database.TagGroupStore.add(tag.id, listOf(f.root.groupAdapter.groupList.first().id))
            val tags = f.openTags()
            f.awaitIo { tags.requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tag_list).adapter!!.itemCount == 1 }
            f.assertMotion(f.root.requireView(), tags.requireView(), true); f.settle()
            val tagView = tags.requireView()
            val members = f.openMembers(tag.id)
            f.awaitIo { members.groupAdapter.itemCount == 1 }
            f.assertMotion(tagView, members.requireView(), true); f.settle()
            assertTrue(members.onMenuItemClick(members.toolbar.menu.findItem(R.id.action_new_group)))
            val intent = shadowOf(f.activity).nextStartedActivity
            assertEquals(io.nekohasekai.sagernet.ui.GroupSettingsActivity::class.java.name, intent.component!!.className)
            assertEquals(0L, intent.getLongExtra(io.nekohasekai.sagernet.ui.GroupSettingsActivity.EXTRA_GROUP_ID, -1))
            assertEquals(tag.id, intent.getLongExtra(io.nekohasekai.sagernet.ui.GroupSettingsActivity.EXTRA_TAG_ID, -1))
            val memberView = members.requireView()
            f.activity.onBackPressedDispatcher.onBackPressed()
            f.awaitTags()
            val backToTags = f.current as TagGroupFragment
            f.awaitIo { backToTags.requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tag_list).adapter!!.itemCount == 1 }
            f.assertMotion(memberView, backToTags.requireView(), false); f.settle()
            assertTrue(tagBackButton(backToTags).performClick()); f.awaitRoot()
            f.assertMotion(backToTags.requireView(), f.root.requireView(), false); f.settle()
        }
    }

    @Test fun realDeleteSnackbarUsesDefaultWindowNoSecondsAndUndoRestoresTagRelations() {
        Fixture().use { f ->
            val tag = io.nekohasekai.sagernet.database.TagGroupStore.create("Undo Flower")
            val rootId = f.root.groupAdapter.groupList.first().id
            io.nekohasekai.sagernet.database.TagGroupStore.add(tag.id, listOf(rootId))
            val tags = f.openTags()
            f.awaitIo { tags.requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tag_list).adapter!!.itemCount == 1 }
            f.assertMotion(f.root.requireView(), tags.requireView(), true); f.settle()
            ReflectionHelpers.callInstanceMethod<Void>(tags, "delete",
                ReflectionHelpers.ClassParameter.from(io.nekohasekai.sagernet.database.TagGroup::class.java, tag))
            f.awaitIo { ReflectionHelpers.getField<com.google.android.material.snackbar.Snackbar?>(tags, "undoSnackbar") != null }
            val bar = ReflectionHelpers.getField<com.google.android.material.snackbar.Snackbar>(tags, "undoSnackbar")
            assertEquals(com.google.android.material.snackbar.Snackbar.LENGTH_LONG, bar.duration)
            val text = bar.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text).text.toString()
            assertTrue(text.contains(tag.name)); assertFalse(text.contains("秒"))
            f.awaitIo { bar.view.findViewById<View>(com.google.android.material.R.id.snackbar_action).isEnabled }
            bar.view.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            f.awaitIo { io.nekohasekai.sagernet.database.TagGroupStore.get(tag.id) != null }
            assertEquals(setOf(rootId), io.nekohasekai.sagernet.database.TagGroupStore.members(tag.id))
            assertEquals(tag, io.nekohasekai.sagernet.database.TagGroupStore.get(tag.id))
        }
    }

    @Test fun repeatedColdMemberPagesNeverLoadBeforeHostAdapterAndUndoOwnerAssignment() {
        Fixture().use { f ->
            val tag = io.nekohasekai.sagernet.database.TagGroupStore.create("Cold repeat")
            io.nekohasekai.sagernet.database.TagGroupStore.add(tag.id, listOf(f.root.groupAdapter.groupList.first().id))
            val tags = f.openTags()
            f.awaitIo { tags.requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tag_list).adapter!!.itemCount == 1 }
            f.assertMotion(f.root.requireView(), tags.requireView(), true); f.settle()
            repeat(6) {
                val old = f.current!!.requireView()
                val member = f.openMembers(tag.id)
                f.awaitIo { member.groupAdapter.itemCount == 1 }
                assertNotNull(member.undoManager)
                assertSame(member.groupAdapter, member.groupListView.adapter)
                f.assertMotion(old, member.requireView(), true); f.settle()
                val outgoing = member.requireView()
                f.activity.onBackPressedDispatcher.onBackPressed(); f.awaitTags()
                val target = f.current as TagGroupFragment
                f.awaitIo { target.requireView().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tag_list).adapter!!.itemCount == 1 }
                f.assertMotion(outgoing, target.requireView(), false); f.settle()
            }
        }
    }

    @Test fun selectingGroupMenuWhileOnTagsResolvesRootButSelectingActualRootIsIdempotent() {
        Fixture().use { f ->
            val tags = f.openTags()
            f.awaitIo { tags.requireView().findViewById<View>(R.id.tag_empty).visibility == View.VISIBLE }
            f.assertMotion(f.root.requireView(), tags.requireView(), forward = true)
            f.settle()
            val menu = ReflectionHelpers.getField<CompactNavigationMenu>(f.activity, "navigation")
            assertTrue(f.activity.onNavigationItemSelected(menu.menu.findItem(R.id.nav_group)))
            f.awaitRoot()
            f.assertMotion(tags.requireView(), f.root.requireView(), forward = true)
            f.settle()
            val view = f.root.requireView()
            assertTrue(f.activity.displayFragmentWithId(R.id.nav_group))
            f.frames(4)
            assertSame(f.root, f.current)
            assertSame(view, f.root.requireView())
            assertEquals(0f, view.translationX, 0f)
            assertEquals(1, f.activity.supportFragmentManager.fragments.filterIsInstance<GroupFragment>().size)
        }
    }
}
