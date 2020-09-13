/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote

import com.intellij.openapi.projectRoots.Sdk

/**
 * Injected by [RsRemoteSdkEditor] to edit remote toolchain.
 */
interface RsEditRemoteToolchainDialog {
    fun setEditing(data: RsRemoteSdkAdditionalData)
    fun setSdkName(name: String)
    fun showAndGet(): Boolean
    fun getSdk(): Sdk?
}
