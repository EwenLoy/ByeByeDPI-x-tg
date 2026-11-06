package io.github.romanvht.byedpi.ewenloy.tgws

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface ProxyLibrary : Library {
    companion object {
        val INSTANCE = Native.load("tgwsproxy", ProxyLibrary::class.java) as ProxyLibrary
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetDisableSecure(v: Int)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeProxy {
    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int {
        return ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, verbose)
    }

    fun stopProxy(): Int {
        return ProxyLibrary.INSTANCE.StopProxy()
    }

    fun setPoolSize(size: Int) {
        ProxyLibrary.INSTANCE.SetPoolSize(size)
    }

    /**
     * false — соединения к Cloudflare идут по TLS на 443 (нормальный режим).
     * true — плейн-HTTP на 80: годно только для отладки, DPI рвёт такое за десятки секунд.
     */
    fun setDisableSecure(disabled: Boolean) {
        ProxyLibrary.INSTANCE.SetDisableSecure(if (disabled) 1 else 0)
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        ProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        ProxyLibrary.INSTANCE.SetCfProxyConfig(
            if (enabled) 1 else 0,
            if (priority) 1 else 0,
            userDomain
        )
    }

    /** Returns the full secret with correct prefix (dd or ee+domain_hex) */
    fun getSecretWithPrefix(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }

    fun getStats(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetStats() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }
}
