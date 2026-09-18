package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Identity vet for the host plugin Update path (BossConsole#927).
 *
 * Both store installers refuse a downloaded jar whose manifest declares a different plugin
 * (`StoreVersionInstaller.activate`, `StoreMissingDependencyInstaller`), because nothing
 * binds a store row to the plugin id its jar declares. The host's own Update button had no
 * such gate anywhere on its chain: the downloaded jar went straight to the swap, which
 * force-unloads the running plugin FIRST - so an identity-mismatched jar uninstalled the
 * real plugin, and a jar declaring `ai.rever.boss.plugin.api` reached
 * `DynamicPluginManager.hotSwapApiLayer`, a process-wide unload/swap/reload that
 * `PluginDependencyResolution.NOT_USER_INSTALLABLE` exists to keep out of a one-click
 * dialog.
 *
 * The installers additionally refuse NOT_USER_INSTALLABLE ids outright, because a store
 * INSTALL dialog must never install the api layer. The Update chain differs in exactly one
 * way: the api plugin's own row IS the designed route by which a newer api jar arrives at
 * runtime (`installPlugin` routes it into the hot swap and expects this bridge to have
 * just uninstalled the old entry). So the gate here is the identity equality itself - a
 * protected id is refused under every row except its own, which is what "declares the
 * plugin being updated" below already means.
 */
internal object UpdateJarIdentityVet {
    private val logger = BossLogger.forComponent("UpdateJarIdentityVet")

    /**
     * Accept [jarPath] only when its manifest declares exactly [pluginId].
     *
     * Fails closed on an unreadable manifest too: a jar whose declared identity cannot be
     * established gives the update nothing to verify, and the caller keeps the running
     * plugin installed rather than swapping in bytes it cannot name. The jar itself is the
     * caller's to discard - the update bridge deletes a rejected download the same way it
     * deletes a failed one, before the next directory scan could pick it up.
     */
    fun vet(
        pluginId: String,
        jarPath: String,
    ): Result<Unit> {
        val declared = runCatching { PluginManifestReader.readFromJar(jarPath) }.getOrNull()
        val declaredId = declared?.pluginId
        if (declaredId == pluginId) return Result.success(Unit)

        logger.warn(
            LogCategory.SYSTEM,
            "Refusing an update jar that does not declare the plugin it updates",
            mapOf(
                "expected" to pluginId,
                "declared" to (declaredId ?: "unreadable"),
                "jarPath" to jarPath,
            ),
        )
        return Result.failure(
            IllegalStateException(
                "The update for $pluginId did not install as $pluginId - the downloaded jar " +
                    "declares \"${declaredId ?: "an unreadable manifest"}\". The store entry may be " +
                    "wrong; the running version was kept.",
            ),
        )
    }
}
