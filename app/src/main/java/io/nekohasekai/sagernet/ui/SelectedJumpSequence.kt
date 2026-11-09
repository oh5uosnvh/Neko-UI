package io.nekohasekai.sagernet.ui

/** Toggle only consecutive jumps to the SAME selected node, never stale unrelated taps. */
internal class SelectedJumpSequence {
    private var selected = 0L
    private var group = 0L
    private var armed = false
    fun reset() { selected = 0L; group = 0L; armed = false }
    fun returnToFirst(profileId: Long, selectedGroupId: Long, visibleGroupId: Long): Boolean {
        if (profileId <= 0L || selectedGroupId <= 0L) { reset(); return true }
        val second = armed && selected == profileId && group == selectedGroupId && visibleGroupId == group
        selected = profileId
        group = selectedGroupId
        armed = !second
        return second
    }
}
