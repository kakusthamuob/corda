package net.corda.core.transactions

import net.corda.core.CordaInternal
import net.corda.core.DeleteForDJVM
import net.corda.core.KeepForDJVM
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.TransactionSignature
import net.corda.core.crypto.sha256
import net.corda.core.identity.Party
import net.corda.core.node.NetworkParameters
import net.corda.core.node.ServiceHub
import net.corda.core.node.ServicesForResolution
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.transactions.NotaryChangeWireTransaction.Component.*
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.toBase58String
import java.security.PublicKey

/**
 * A special transaction for changing the notary of a state. It only needs specifying the state(s) as input(s),
 * old and new notaries. Output states can be computed by applying the notary modification to corresponding inputs
 * on the fly.
 */
@CordaSerializable
@KeepForDJVM
data class NotaryChangeWireTransaction(
        /**
         * Contains all of the transaction components in serialized form.
         * This is used for calculating the transaction id in a deterministic fashion, since re-serializing properties
         * may result in a different byte sequence depending on the serialization context.
         */
        val serializedComponents: List<OpaqueBytes>
) : CoreTransaction() {
    override val inputs: List<StateRef> = serializedComponents[INPUTS.ordinal].deserialize()
    override val references: List<StateRef> = emptyList()
    override val notary: Party = serializedComponents[NOTARY.ordinal].deserialize()

    /** Identity of the notary service to reassign the states to.*/
    val newNotary: Party = serializedComponents[NEW_NOTARY.ordinal].deserialize()

    override val networkParametersHash: SecureHash? by lazy {
        if (serializedComponents.size >= PARAMETERS_HASH.ordinal + 1) {
            serializedComponents[PARAMETERS_HASH.ordinal].deserialize<SecureHash>()
        } else null
    }

    /**
     * This transaction does not contain any output states, outputs can be obtained by resolving a
     * [NotaryChangeLedgerTransaction] and applying the notary modification to inputs.
     */
    override val outputs: List<TransactionState<ContractState>>
        get() = throw UnsupportedOperationException("NotaryChangeWireTransaction does not contain output states, " +
                "outputs can only be obtained from a resolved NotaryChangeLedgerTransaction")

    init {
        check(inputs.isNotEmpty()) { "A notary change transaction must have inputs" }
        check(notary != newNotary) { "The old and new notaries must be different – $newNotary" }
        checkBaseInvariants()
    }

    /**
     * A privacy salt is not really required in this case, because we already used nonces in normal transactions and
     * thus input state refs will always be unique. Also, filtering doesn't apply on this type of transactions.
     */
    override val id: SecureHash by lazy {
        serializedComponents.map { component ->
            component.bytes.sha256()
        }.reduce { combinedHash, componentHash ->
            combinedHash.hashConcat(componentHash)
        }
    }

    /** Resolves input states and network parameters and builds a [NotaryChangeLedgerTransaction]. */
    @DeleteForDJVM
    fun resolve(services: ServicesForResolution, sigs: List<TransactionSignature>): NotaryChangeLedgerTransaction {
        val resolvedInputs = services.loadStates(inputs.toSet()).toList()
        val hashToResolve = networkParametersHash ?: services.networkParametersStorage.defaultParametersHash
        val resolvedNetworkParameters = services.networkParametersStorage.readParametersFromHash(hashToResolve) ?: throw TransactionResolutionException(id)
        return NotaryChangeLedgerTransaction(resolvedInputs, notary, newNotary, id, sigs, resolvedNetworkParameters)
    }

    /** Resolves input states and builds a [NotaryChangeLedgerTransaction]. */
    @DeleteForDJVM
    fun resolve(services: ServiceHub, sigs: List<TransactionSignature>) = resolve(services as ServicesForResolution, sigs)

    /**
     * This should return a serialized virtual output state, that will be used to verify spending transactions.
     * The binary output should not depend on the classpath of the node that is verifying the transaction.
     *
     * Ideally the serialization engine would support partial deserialization so that only the Notary ( and the encumbrance can be replaced from the binary input state)
     *
     *
     * TODO - currently this uses the main classloader.
     */
    @CordaInternal
    internal fun resolveOutputComponent(services: ServicesForResolution, stateRef: StateRef): SerializedBytes<TransactionState<ContractState>> {
        return services.loadState(stateRef).serialize()
    }

    enum class Component {
        INPUTS, NOTARY, NEW_NOTARY, PARAMETERS_HASH
    }

    @Deprecated("Required only for backwards compatibility purposes. This type of transaction should not be constructed outside Corda code.", ReplaceWith("NotaryChangeTransactionBuilder"), DeprecationLevel.WARNING)
    constructor(inputs: List<StateRef>, notary: Party, newNotary: Party) : this(listOf(inputs, notary, newNotary).map { it.serialize() })
}

/**
 * A notary change transaction with fully resolved inputs and signatures. In contrast with a regular transaction,
 * signatures are checked against the signers specified by input states' *participants* fields, so full resolution is
 * needed for signature verification.
 */
@KeepForDJVM
data class NotaryChangeLedgerTransaction(
        override val inputs: List<StateAndRef<ContractState>>,
        override val notary: Party,
        val newNotary: Party,
        override val id: SecureHash,
        override val sigs: List<TransactionSignature>,
        override val networkParameters: NetworkParameters
) : FullTransaction(), TransactionWithSignatures {
    init {
        checkEncumbrances()
        checkNewNotaryWhitelisted()
    }

    /**
     * Check that the output notary is whitelisted.
     *
     * Note that for this transaction type we do not require the input notary to be whitelisted to support network merging.
     * For all other transaction types this is enforced.
     */
    private fun checkNewNotaryWhitelisted() {
        val notaryWhitelist = networkParameters.notaries.map { it.identity }
        check(newNotary in notaryWhitelist) {
            "The output notary $newNotary is not whitelisted in the attached network parameters."
        }
    }

    override val references: List<StateAndRef<ContractState>> = emptyList()

    /** We compute the outputs on demand by applying the notary field modification to the inputs. */
    override val outputs: List<TransactionState<ContractState>>
        get() = computeOutputs()

    private fun computeOutputs(): List<TransactionState<ContractState>> {
        val inputPositionIndex: Map<StateRef, Int> = inputs.mapIndexed { index, stateAndRef -> stateAndRef.ref to index }.toMap()
        return inputs.map { (state, ref) ->
            if (state.encumbrance != null) {
                val encumbranceStateRef = StateRef(ref.txhash, state.encumbrance)
                val encumbrancePosition = inputPositionIndex[encumbranceStateRef]
                        ?: throw IllegalStateException("Unable to generate output states – transaction not constructed correctly.")
                state.copy(notary = newNotary, encumbrance = encumbrancePosition)
            } else state.copy(notary = newNotary)
        }
    }

    override val requiredSigningKeys: Set<PublicKey>
        get() = inputs.flatMap { it.state.data.participants }.map { it.owningKey }.toSet() + notary.owningKey

    override fun getKeyDescriptions(keys: Set<PublicKey>): List<String> {
        return keys.map { it.toBase58String() }
    }

    /**
     * Check that encumbrances have been included in the inputs.
     */
    private fun checkEncumbrances() {
        val encumberedStates = inputs.asSequence().filter { it.state.encumbrance != null }.associateBy { it.ref }
        if (encumberedStates.isNotEmpty()) {
            inputs.forEach { (state, ref) ->
                if (StateRef(ref.txhash, state.encumbrance!!) !in encumberedStates) {
                    throw TransactionVerificationException.TransactionMissingEncumbranceException(
                            id,
                            state.encumbrance,
                            TransactionVerificationException.Direction.INPUT)
                }
            }
        }
    }
}
