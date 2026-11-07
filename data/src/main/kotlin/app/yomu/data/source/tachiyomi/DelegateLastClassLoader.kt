// Adapted from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0. See compat/tachiyomi/NOTICE.
package app.yomu.data.source.tachiyomi

import android.os.Build
import dalvik.system.DelegateLastClassLoader
import dalvik.system.PathClassLoader
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * Class loader for Tachiyomi/Mihon extensions: boot classes first, then the extension's own dex, then the app.
 * Extensions bundle helper libraries of their own, which must win over same-named classes of the host.
 */
@Suppress("FunctionName")
internal fun ExtensionClassLoader(dexPath: String, parent: ClassLoader): ClassLoader =
    // ART maps app images compiled for the platform loader; before Android 17 a damaged image could crash every
    // launch, so older versions use the equivalent backport below (the same choice Mihon makes).
    if (Build.VERSION.SDK_INT >= 37) DelegateLastClassLoader(dexPath, null, parent) else DelegateLastPathClassLoader(dexPath, parent)

private class DelegateLastPathClassLoader(dexPath: String, parent: ClassLoader) : PathClassLoader(dexPath, null, parent) {
    private val boot: ClassLoader? = Any::class.java.classLoader

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let { return it }
        if (boot != null) {
            try {
                return boot.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        val own = try {
            return findClass(name)
        } catch (e: ClassNotFoundException) {
            e
        }
        return try {
            parent.loadClass(name)
        } catch (_: ClassNotFoundException) {
            throw own
        }
    }

    override fun getResource(name: String?): URL? = boot?.getResource(name) ?: findResource(name) ?: parent?.getResource(name)

    override fun getResources(name: String?): Enumeration<URL> = Collections.enumeration(
        buildList {
            boot?.getResources(name)?.let { addAll(it.toList()) }
            findResources(name)?.let { addAll(it.toList()) }
            parent?.getResources(name)?.let { addAll(it.toList()) }
        },
    )
}
