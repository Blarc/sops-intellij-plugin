package com.github.blarc.sops.intellij.plugin

import com.intellij.DynamicBundle
import com.intellij.ide.browsers.BrowserLauncher
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.SopsBundle"

object SopsBundle : DynamicBundle(BUNDLE) {

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)

    @Suppress("unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)

    fun openRepository() {
        BrowserLauncher.instance.open("https://github.com/Blarc/sops-intellij-plugin");
    }

}
