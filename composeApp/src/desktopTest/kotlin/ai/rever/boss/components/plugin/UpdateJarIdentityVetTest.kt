package ai.rever.boss.components.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [UpdateJarIdentityVet] accepts only a jar that declares the id of the plugin being
 * updated (BossConsole#927).
 *
 * The mismatch the issue reports is a store row for a normal plugin serving an api-id jar,
 * which - unvetted - reached `installPlugin` and triggered the process-wide api hot swap.
 * The api id under its OWN row is the designed way a newer api layer arrives at runtime,
 * so that path must keep passing; a protected id under anyone else's row is a mismatch
 * like any other.
 */
class UpdateJarIdentityVetTest {
    private val pluginId = "ai.rever.boss.plugin.dynamic.probe"

    @Test
    fun `a jar declaring the plugin being updated is accepted`() =
        withTempDir { dir ->
            val jar = PluginJarTestFixtures.writeJar(dir, "probe-2.0.0.jar", pluginId, "2.0.0")

            val result = UpdateJarIdentityVet.vet(pluginId, jar.absolutePath)

            assertTrue(result.isSuccess, "a matching-id update jar must proceed")
        }

    @Test
    fun `a protected-id jar is rejected under any other plugin's row`() =
        withTempDir { dir ->
            PluginDependencyResolution.NOT_USER_INSTALLABLE.forEach { protected ->
                val jar = PluginJarTestFixtures.writeJar(dir, "hijack-99.0.0.jar", protected, "99.0.0")

                val result = UpdateJarIdentityVet.vet(pluginId, jar.absolutePath)

                assertTrue(result.isFailure, "$protected must not install over $pluginId's update row")
            }
        }

    @Test
    fun `an api jar under the api plugin's own row still passes`() =
        withTempDir { dir ->
            val apiId = "ai.rever.boss.plugin.api"
            val jar = PluginJarTestFixtures.writeJar(dir, "api-1.1.0.jar", apiId, "1.1.0")

            val result = UpdateJarIdentityVet.vet(apiId, jar.absolutePath)

            assertTrue(result.isSuccess, "the api plugin's own update is the designed runtime api-swap route")
        }

    @Test
    fun `a jar with no readable manifest is rejected`() =
        withTempDir { dir ->
            val jar = File(dir, "manifest-less.jar")
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("unrelated.txt"))
                zip.write("not a plugin manifest".toByteArray())
                zip.closeEntry()
            }

            val result = UpdateJarIdentityVet.vet(pluginId, jar.absolutePath)

            assertTrue(result.isFailure, "identity that cannot be read must fail closed")
        }

    @Test
    fun `a jar that is not there is rejected`() =
        withTempDir { dir ->
            val result = UpdateJarIdentityVet.vet(pluginId, File(dir, "absent.jar").absolutePath)

            assertTrue(result.isFailure, "a missing jar has no identity to accept")
        }
}
