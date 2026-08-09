/*
 * NativeLib - KeePassDX crypto layer
 *
 * This project uses Bouncy Castle Java implementations instead of JNI/C native code.
 * NativeLib always returns false so CipherFactory falls back to Java crypto.
 */
package com.kunzisoft.encrypt

object NativeLib {

    fun loaded(): Boolean {
        // JNI native libraries are not compiled in this project.
        // CipherFactory will fall back to Bouncy Castle Java implementations.
        return false
    }

    fun init(): Boolean {
        return false
    }
}
