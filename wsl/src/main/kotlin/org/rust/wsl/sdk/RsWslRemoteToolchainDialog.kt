/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.wsl.sdk

import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.messages.showProcessExecutionErrorDialog
import org.rust.openapiext.RsExecutionException
import org.rust.remote.sdk.RsEditRemoteToolchainDialog
import org.rust.remote.sdk.RsRemoteSdkAdditionalData
import org.rust.wsl.sdk.add.RsAddWslPanel
import javax.swing.JComponent

class RsWslRemoteToolchainDialog(
    private val project: Project,
    existingSdks: List<Sdk>
) : RsEditRemoteToolchainDialog, DialogWrapper(project, true) {
    private val panel: RsAddWslPanel = RsAddWslPanel(existingSdks)

    init {
        init()
        title = panel.panelName
    }

    override fun setEditing(data: RsRemoteSdkAdditionalData) {
        panel.configure(data)
    }

    override fun setSdkName(name: String) {}

    override fun createCenterPanel(): JComponent = panel

    override fun showAndGet(): Boolean {
        val result = super.showAndGet()
        try {
            // Creates SDK and fills it with data from form
            // This sdk will be used as source to copy data to existing one
            panel.complete()
        } catch (e: ExecutionException) {
            val exception = (e as? RsExecutionException ?: e.cause as? RsExecutionException) ?: throw e
            showProcessExecutionErrorDialog(
                project,
                exception.localizedMessage.orEmpty(),
                exception.command,
                exception.stdout,
                exception.stderr,
                exception.exitCode
            )
            return false
        }
        return result
    }

    override fun getSdk(): Sdk? = panel.sdk
}
