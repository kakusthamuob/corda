package net.corda.bootstrapper

import net.corda.cliutils.CordaCliWrapper
import net.corda.cliutils.start
import net.corda.core.internal.PLATFORM_VERSION
import net.corda.core.internal.requirePackageValid
import net.corda.nodeapi.internal.crypto.loadKeyStore
import net.corda.nodeapi.internal.network.NetworkBootstrapper
import net.corda.nodeapi.internal.network.NetworkBootstrapper.CopyCordapps
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStoreException
import java.security.PublicKey

fun main(args: Array<String>) {
    NetworkBootstrapperRunner().start(args)
}

class NetworkBootstrapperRunner : CordaCliWrapper("bootstrapper", "Bootstrap a local test Corda network using a set of node configuration files and CorDapp JARs") {
    @Option(
            names = ["--dir"],
            description = [
                "Root directory containing the node configuration files and CorDapp JARs that will form the test network.",
                "It may also contain existing node directories."
            ]
    )
    var dir: Path = Paths.get(".")

    @Option(names = ["--no-copy"], hidden = true, description = ["""DEPRECATED. Don't copy the CorDapp JARs into the nodes' "cordapps" directories."""])
    var noCopy: Boolean? = null

    @Option(names = ["--copy-cordapps"], description = ["Whether or not to copy the CorDapp JARs into the nodes' 'cordapps' directory. \${COMPLETION-CANDIDATES}"])
    var copyCordapps: NetworkBootstrapper.CopyCordapps = NetworkBootstrapper.CopyCordapps.OnFirstRun

    @Option(names = ["--minimum-platform-version"], description = ["The minimumPlatformVersion to use in the network-parameters."])
    var minimumPlatformVersion = PLATFORM_VERSION

    @Option(names = ["--register-package-owner"],
            converter = [PackageOwnerConverter::class],
            description = [
                "Register owner of Java package namespace in the network-parameters.",
                "Format: [java-package-namespace;keystore-file;password;alias]",
                "         `java-package-namespace` is case insensitive and cannot be a sub-package of an existing registered namespace",
                "         `keystore-file` refers to the location of key store file containing the signed certificate as generated by the Java 'keytool' tool (see https://docs.oracle.com/javase/8/docs/technotes/tools/windows/keytool.html)",
                "         `password` to open the key store",
                "         `alias` refers to the name associated with a certificate containing the public key to be associated with the package namespace"
            ])
    var registerPackageOwnership: List<PackageOwner> = mutableListOf()

    @Option(names = ["--unregister-package-owner"],
            description = [
                "Unregister owner of Java package namespace in the network-parameters.",
                "Format: [java-package-namespace]",
                "         `java-package-namespace` is case insensitive and cannot be a sub-package of an existing registered namespace"
            ])
    var unregisterPackageOwnership: List<String> = mutableListOf()

    override fun runProgram(): Int {
        if (noCopy != null) {
            printlnWarn("The --no-copy parameter has been deprecated and been replaced with the --copy-cordapps parameter.")
            copyCordapps = if (noCopy == true) CopyCordapps.No else CopyCordapps.Yes
        }

        NetworkBootstrapper().bootstrap(dir.toAbsolutePath().normalize(),
                copyCordapps = copyCordapps,
                minimumPlatformVersion = minimumPlatformVersion,
                packageOwnership = registerPackageOwnership.map { Pair(it.javaPackageName, it.publicKey) }.toMap()
                        .plus(unregisterPackageOwnership.map { Pair(it, null) })
        )
        return 0 //exit code
    }
}


data class PackageOwner(val javaPackageName: String, val publicKey: PublicKey)

/**
 * Converter from String to PackageOwner (String and PublicKey)
 */
class PackageOwnerConverter : CommandLine.ITypeConverter<PackageOwner> {
    override fun convert(packageOwner: String): PackageOwner {
        if (!packageOwner.isBlank()) {
            val packageOwnerSpec = packageOwner.split(";")
            if (packageOwnerSpec.size < 4)
                throw IllegalArgumentException("Package owner must specify 4 elements separated by semi-colon: 'java-package-namespace;keyStorePath;keyStorePassword;alias'")

            // java package name validation
            val javaPackageName = packageOwnerSpec[0]
            requirePackageValid(javaPackageName)

            // cater for passwords that include the argument delimiter field
            val keyStorePassword =
                if (packageOwnerSpec.size > 4)
                    packageOwnerSpec.subList(2, packageOwnerSpec.size-1).joinToString(";")
                else packageOwnerSpec[2]
            try {
                val ks = loadKeyStore(Paths.get(packageOwnerSpec[1]), keyStorePassword)
                try {
                    val publicKey = ks.getCertificate(packageOwnerSpec[packageOwnerSpec.size-1]).publicKey
                    return PackageOwner(javaPackageName,publicKey)
                }
                catch(kse: KeyStoreException) {
                    throw IllegalArgumentException("Keystore has not been initialized for alias ${packageOwnerSpec[3]}")
                }
            }
            catch(kse: KeyStoreException) {
                throw IllegalArgumentException("Password is incorrect or the key store is damaged for keyStoreFilePath: ${packageOwnerSpec[1]} and keyStorePassword: $keyStorePassword")
            }
            catch(e: IOException) {
                throw IllegalArgumentException("Error reading the key store from the file for keyStoreFilePath: ${packageOwnerSpec[1]} and keyStorePassword: $keyStorePassword")
            }
        }
        else throw IllegalArgumentException("Must specify package owner argument: 'java-package-namespace;keyStorePath;keyStorePassword;alias'")
    }
}
