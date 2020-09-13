/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.wsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.util.io.BaseOutputReader
import org.rust.cargo.runconfig.RsProcessHandler

class RsWslProcessHandler(commandLine: GeneralCommandLine) : RsProcessHandler(commandLine) {
    override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.BLOCKING
}
