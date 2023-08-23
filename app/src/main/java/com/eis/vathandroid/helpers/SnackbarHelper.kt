package com.eis.vathandroid.helpers

import android.R
import android.app.Activity
import android.view.View
import android.widget.TextView
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar

/**
 * Helper to manage the sample snackbar. Hides the Android boilerplate code, and exposes simpler
 * methods.
 */
class SnackbarHelper {
    private var messageSnackbar: Snackbar? = null

    private enum class DismissBehavior {
        HIDE, SHOW, FINISH
    }

    private var maxLines = 2
    private var lastMessage = ""
    val isShowing: Boolean
        get() = messageSnackbar != null

    /** Shows a snackbar with a given message.  */
    fun showMessage(activity: Activity, message: String) {
        if (!message.isEmpty() && (!isShowing || lastMessage != message)) {
            lastMessage = message
            show(activity, message, DismissBehavior.HIDE)
        }
    }

    /** Shows a snackbar with a given message, and a dismiss button.  */
    fun showMessageWithDismiss(activity: Activity, message: String) {
        show(activity, message, DismissBehavior.SHOW)
    }

    /**
     * Shows a snackbar with a given error message. When dismissed, will finish the activity. Useful
     * for notifying errors, where no further interaction with the activity is possible.
     */
    fun showError(activity: Activity, errorMessage: String) {
        show(activity, errorMessage, DismissBehavior.FINISH)
    }

    /**
     * Hides the currently showing snackbar, if there is one. Safe to call from any thread. Safe to
     * call even if snackbar is not shown.
     */
    fun hide(activity: Activity) {
        if (!isShowing) {
            return
        }
        lastMessage = ""
        val messageSnackbarToHide = messageSnackbar
        messageSnackbar = null
        activity.runOnUiThread { messageSnackbarToHide!!.dismiss() }
    }

    fun setMaxLines(lines: Int) {
        maxLines = lines
    }

    private fun show(
        activity: Activity, message: String, dismissBehavior: DismissBehavior
    ) {
        activity.runOnUiThread {
            messageSnackbar = Snackbar.make(
                activity.findViewById(R.id.content),
                message,
                Snackbar.LENGTH_INDEFINITE
            )
            messageSnackbar!!.view.setBackgroundColor(BACKGROUND_COLOR)
            if (dismissBehavior != DismissBehavior.HIDE) {
                messageSnackbar!!.setAction(
                    "Dismiss"
                ) { messageSnackbar!!.dismiss() }
                if (dismissBehavior == DismissBehavior.FINISH) {
                    messageSnackbar!!.addCallback(
                        object : BaseCallback<Snackbar?>() {
                            override fun onDismissed(
                                transientBottomBar: Snackbar?,
                                event: Int
                            ) {
                                super.onDismissed(transientBottomBar, event)
                                activity.finish()
                            }
                        })
                }
            }

            messageSnackbar!!.show()
        }
    }

    companion object {
        private const val BACKGROUND_COLOR = -0x40cdcdce
    }
}