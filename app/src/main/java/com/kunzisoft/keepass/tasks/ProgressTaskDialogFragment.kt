/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.tasks

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.kunzisoft.keepass.R
import java.lang.Exception

open class ProgressTaskDialogFragment : DialogFragment(), ProgressTaskUpdater {

    @StringRes
    private var title = UNDEFINED
    @StringRes
    private var message = UNDEFINED
    @StringRes
    private var warning = UNDEFINED

    private var titleView: TextView? = null
    private var messageView: TextView? = null
    private var warningView: TextView? = null
    private var progressView: ProgressBar? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        try {
            activity?.let {
                val builder = AlertDialog.Builder(it)
                // Get the layout inflater
                val inflater = it.layoutInflater

                // Inflate and set the layout for the dialog
                // Pass null as the parent view because its going in the dialog layout
                @SuppressLint("InflateParams")
                val root = inflater.inflate(R.layout.fragment_progress, null)
                builder.setView(root)

                titleView = root.findViewById(R.id.progress_dialog_title)
                messageView = root.findViewById(R.id.progress_dialog_message)
                warningView = root.findViewById(R.id.progress_dialog_warning)
                progressView = root.findViewById(R.id.progress_dialog_bar)

                updateTitle(title)
                updateMessage(message)
                updateWarning(warning)

                isCancelable = false

                return builder.create()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create progress dialog")
        }
        return super.onCreateDialog(savedInstanceState)
    }

    fun setTitle(@StringRes titleId: Int) {
        this.title = titleId
    }

    private fun updateView(textView: TextView?, @StringRes resId: Int) {
        activity?.runOnUiThread {
            if (resId == UNDEFINED) {
                textView?.visibility = View.GONE
            } else {
                textView?.setText(resId)
                textView?.visibility = View.VISIBLE
            }
        }
    }

    fun updateTitle(@StringRes resId: Int) {
        this.title = resId
        updateView(titleView, title)
    }

    override fun updateMessage(@StringRes resId: Int) {
        this.message = resId
        updateView(messageView, message)
    }

    fun updateWarning(@StringRes resId: Int) {
        this.warning = resId
        updateView(warningView, warning)
    }

    companion object {
        private val TAG = ProgressTaskDialogFragment::class.java.simpleName
        const val PROGRESS_TASK_DIALOG_TAG = "progressDialogFragment"
        const val UNDEFINED = -1
    }
}
