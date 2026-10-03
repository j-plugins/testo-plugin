package com.github.xepozz.testo.openide

import com.github.xepozz.testo.coverage.resolveCoverageSourcePath
import com.intellij.testFramework.UsefulTestCase
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality

/**
 * Which file a path from a coverage report lands on: the translations of every profile, active first, as the core
 * takes them — the first that names an existing file. The profile list is shared by all projects, so a translation into
 * another project never counts.
 */
class TestoCoverageSourcePathsTest : UsefulTestCase() {
    private val root = "/work/app"
    private val local = FakeLaunchEnvironment()

    private fun docker(hostRoot: String, containerRoot: String) =
        FakeLaunchEnvironment(PhpLaunchLocality.Container("host.docker.internal"), mapOf(hostRoot to containerRoot))

    private fun resolve(path: String, environments: List<PhpLaunchEnvironment>, existing: Set<String>): String =
        resolveCoverageSourcePath(path, OpenIdeTestoPhp.pathMappersOf(root, environments)) { it in existing }

    fun testAContainerReportIsTranslatedWhileALocalProfileIsActive() {
        assertEquals(
            "/work/app/src/Service.php",
            resolve("/app/src/Service.php", listOf(local, docker(root, "/app")), setOf("/work/app/src/Service.php")),
        )
    }

    fun testATranslationIntoAnotherProjectIsNull() {
        val neighbour = docker("/elsewhere/shop", "/app")

        assertNull(OpenIdeTestoPhp.pathMappersOf(root, listOf(neighbour)).single()("/app/src/Service.php"))
        assertEquals(
            "Nothing else translates it: the path stays",
            "/app/src/Service.php",
            resolve("/app/src/Service.php", listOf(neighbour), setOf("/elsewhere/shop/src/Service.php")),
        )
    }

    fun testOfTwoExistingFilesInTwoProjectsThisProjectsWins() {
        val neighbourFirst = listOf(docker("/elsewhere/shop", "/app"), docker(root, "/app"))

        assertEquals(
            "/work/app/src/Service.php",
            resolve("/app/src/Service.php", neighbourFirst, setOf("/elsewhere/shop/src/Service.php", "/work/app/src/Service.php")),
        )
    }

    fun testOfTwoExistingFilesInThisProjectTheActiveProfileWins() {
        val activeFirst = listOf(docker(root, "/app"), docker("$root/packages/api", "/app"))

        assertEquals(
            "/work/app/src/Service.php",
            resolve("/app/src/Service.php", activeFirst, setOf("/work/app/src/Service.php", "/work/app/packages/api/src/Service.php")),
        )
    }

    fun testAWslTranslationWithBackslashesStaysInsideTheProject() {
        val wsl = object : PhpLaunchEnvironment by FakeLaunchEnvironment(PhpLaunchLocality.WslDistribution("Ubuntu")) {
            override val locality: PhpLaunchLocality = PhpLaunchLocality.WslDistribution("Ubuntu")
            override fun toHost(pathInEnvironment: String): String =
                if (pathInEnvironment.startsWith("/mnt/w/app/")) "\\work\\app\\" + pathInEnvironment.removePrefix("/mnt/w/app/").replace('/', '\\')
                else pathInEnvironment
        }

        assertEquals(
            "/work/app/src/Service.php",
            resolve("/mnt/w/app/src/Service.php", listOf(wsl), setOf("/work/app/src/Service.php")),
        )
    }

    fun testAnotherProjectsDockerRunLeadingToNoFileIsSkipped() {
        val neighbourFirst = listOf(docker(root, "/var/www"), docker(root, "/var/www/html"))

        assertEquals(
            "/work/app/src/Service.php",
            resolve("/var/www/html/src/Service.php", neighbourFirst, setOf("/work/app/src/Service.php")),
        )
    }
}
