package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.res.Configuration

/** Resource-only context that survives Fragment detach without retaining its Activity. */
internal object AboutResourceContext {
    fun snapshot(context: Context): Context {
        val application = context.applicationContext ?: context
        return application.createConfigurationContext(Configuration(context.resources.configuration))
    }

    fun resolve(activityContext: Context?, attachedSnapshot: Context?, application: Context): Context =
        attachedSnapshot ?: snapshot(activityContext ?: application)

    fun versionLabel(context: Context, name: String): String =
        context.getString(io.nekohasekai.sagernet.R.string.version_x, name)
}
