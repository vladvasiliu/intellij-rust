/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.util.DownloadResult
import org.rust.ide.notifications.showBalloon

class InstallComponentAction(private val componentName: String) : DumbAwareAction("Install") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val rustup = project.toolchain?.rustup() ?: return
        Notification.get(e).expire()
        object : Task.Backgroundable(project, "Installing $componentName...") {
            override fun shouldStartInBackground(): Boolean = false
            override fun run(indicator: ProgressIndicator) {
                val result = rustup.downloadComponent(myProject, componentName) as? DownloadResult.Err ?: return
                myProject.showBalloon(result.error, NotificationType.ERROR)
            }
        }.queue()
    }
}
