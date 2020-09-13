/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.wsl

import com.intellij.wsl.WSLCredentialsContribution
import org.rust.remote.RsCredentialsContribution

class RsWslCredentialsContribution : WSLCredentialsContribution<RsCredentialsContribution>(),
                                     RsCredentialsContribution {
    override fun getLanguageContributionClass() = RsCredentialsContribution::class.java
    override fun getLanguageContribution() = this
}
