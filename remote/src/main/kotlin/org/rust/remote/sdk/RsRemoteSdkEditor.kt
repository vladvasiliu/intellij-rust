/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote.sdk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk

/**
 * EP to create custom [RsEditRemoteToolchainDialog] to edit SDK.
 */
interface RsRemoteSdkEditor {
    fun supports(data: RsRemoteSdkAdditionalData): Boolean
    fun createSdkEditorDialog(project: Project, existingSdks: List<Sdk>): RsEditRemoteToolchainDialog

    companion object {
        private val EP: ExtensionPointName<RsRemoteSdkEditor> =
            ExtensionPointName.create<RsRemoteSdkEditor>("org.rust.remoteSdkEditor")

        private fun forData(data: RsRemoteSdkAdditionalData): RsRemoteSdkEditor? =
            EP.extensions.find { it.supports(data) }

        fun sdkEditor(
            data: RsRemoteSdkAdditionalData,
            project: Project,
            existingSdks: List<Sdk>
        ): RsEditRemoteToolchainDialog? = forData(data)?.createSdkEditorDialog(project, existingSdks)
    }
}
