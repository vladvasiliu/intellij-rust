/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve.ref

import com.intellij.util.containers.HashingStrategy

@Suppress("UnstableApiUsage")
fun <T> canonicalStrategy(): HashingStrategy<T> = HashingStrategy.canonical()
