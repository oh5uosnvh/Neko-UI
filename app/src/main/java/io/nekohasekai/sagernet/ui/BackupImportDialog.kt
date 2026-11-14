package io.nekohasekai.sagernet.ui

import android.view.LayoutInflater
import io.nekohasekai.sagernet.databinding.LayoutImportBinding

internal object BackupImportDialog {
    /** Options do not depend on which optional sections happen to be in the input. */
    fun inflate(inflater: LayoutInflater): LayoutImportBinding =
        LayoutImportBinding.inflate(inflater)
}
