/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry

/**
 * Same as [com.intellij.execution.process.KillableColoredProcessHandler], but uses [RsAnsiEscapeDecoder].
 */
open class RsProcessHandler(commandLine: GeneralCommandLine) : KillableProcessHandler(commandLine, SOFT_KILL_ON_WIN),
                                                               AnsiEscapeDecoder.ColoredTextAcceptor {
    private val decoder: AnsiEscapeDecoder = RsAnsiEscapeDecoder()

    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        decoder.escapeText(text, outputType, this)
    }

    override fun coloredTextAvailable(text: String, attributes: Key<*>) {
        super.notifyTextAvailable(text, attributes)
    }

    override fun shouldDestroyProcessRecursively(): Boolean = true

    companion object {
        private val SOFT_KILL_ON_WIN: Boolean = Registry.`is`("kill.windows.processes.softly", false)

        fun create(commandLine: GeneralCommandLine): RsProcessHandler {
            val handler = RsProcessHandler(commandLine)
            ProcessTerminatedListener.attach(handler)
            return handler
        }
    }
}
