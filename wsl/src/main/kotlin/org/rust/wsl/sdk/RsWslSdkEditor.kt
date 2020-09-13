/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.wsl.sdk

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import org.rust.remote.RsEditRemoteToolchainDialog
import org.rust.remote.RsRemoteSdkAdditionalData
import org.rust.remote.RsRemoteSdkEditor
import org.rust.wsl.isWsl

class RsWslSdkEditor : RsRemoteSdkEditor {

    override fun supports(data: RsRemoteSdkAdditionalData): Boolean = data.isWsl

    override fun createSdkEditorDialog(project: Project, existingSdks: List<Sdk>): RsEditRemoteToolchainDialog =
        RsWslRemoteToolchainDialog(project, existingSdks)
}
