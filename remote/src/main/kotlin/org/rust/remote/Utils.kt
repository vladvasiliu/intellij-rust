/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote

import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Ref
import com.intellij.remote.RemoteSdkException
import com.intellij.util.ui.UIUtil
import org.rust.cargo.toolchain.RustToolchain
import org.rust.cargo.toolchain.RustcVersion
import org.rust.openapiext.RsExecutionException
import org.rust.remote.sdk.RsRemoteSdkAdditionalData
import org.rust.remote.sdk.RsRemoteToolchainFactory

class RsUnsupportedSdkTypeException : RuntimeException("Unsupported Rust SDK type")

fun createAndInitRemoteSdk(
    data: RsRemoteSdkAdditionalData,
    existingSdks: Collection<Sdk>,
    suggestedName: String? = null
): Sdk {
    // We do not pass `sdkName` so that `createRemoteSdk` generates it by itself
    val remoteSdk = RsRemoteToolchainFactory.createRemoteSdk(null, data, suggestedName, existingSdks)
    RsRemoteToolchainFactory.initSdk(remoteSdk, null, null)
    return remoteSdk
}

fun getRemoteToolchainVersion(
    project: Project?,
    data: RsRemoteSdkAdditionalData,
    nullForUnparsableVersion: Boolean
): RustToolchain.VersionInfo? {
    val result = Ref.create<RustcVersion>(null)
    val exception = Ref.create<RemoteSdkException>(null)
    val task: Task.Modal = object : Task.Modal(project, "Getting Remote Toolchain Version", true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val command = arrayOf("rustc", "--version", "--verbose")
                val processOutput = RsRemoteProcessStarterManager.getManager(data)
                    .executeRemoteProcess(myProject, command, null, data, RsRemotePathMapper())
                if (processOutput.exitCode == 0) {
                    val rustcVersion = RustcVersion.parseRustcVersion(processOutput.stdoutLines)
                    if (rustcVersion != null || nullForUnparsableVersion) {
                        result.set(rustcVersion)
                        return
                    }
                }
                exception.set(createException(processOutput, command))
            } catch (e: Exception) {
                exception.set(RemoteSdkException.cantObtainRemoteCredentials(e))
            }
        }
    }

    if (!ProgressManager.getInstance().hasProgressIndicator()) {
        UIUtil.invokeAndWaitIfNeeded(Runnable { ProgressManager.getInstance().run(task) })
    } else {
        task.run(ProgressManager.getInstance().progressIndicator)
    }

    if (!exception.isNull) {
        throw exception.get()
    }

    return RustToolchain.VersionInfo(result.get())
}

private fun createException(processOutput: ProcessOutput, command: Array<String>): RemoteSdkException {
    val exception = RsExecutionException("Can't obtain Rust version", command.first(), arrayListOf(*command), processOutput)
    return RemoteSdkException.cantObtainRemoteCredentials(exception)
}
