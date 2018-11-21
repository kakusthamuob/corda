package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.mixins.WithMockNet
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.FetchDataFlow
import net.corda.core.internal.FetchNetworkParametersFlow
import net.corda.core.internal.SignedParametersByHash
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.makeUnique
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import org.junit.AfterClass
import org.junit.Test

class FetchParametersFlowTests : WithMockNet {
    companion object {
        val classMockNet = InternalMockNetwork()

        @JvmStatic
        @AfterClass
        fun cleanUp() = classMockNet.stopNodes()
    }

    override val mockNet = classMockNet

    // Test nodes
    private val aliceNode = makeNode(ALICE_NAME)
    private val bobNode = makeNode(BOB_NAME)
    private val alice = aliceNode.info.singleIdentity()

    @Test
    fun `download and store`() {
        //alice has transaction with network parameters hash sth
        // add it to storage
        // SIMILAR to the attachment flow download and store
    }

    @Test
    fun `incorrectly singed parameters`() {
        // alice has transaction with network parameters hash
        // in storage it's signed with diffetent trust root (i.e. transaction from different network!)
        // fail with signature verification
    }

    @Test
    fun `missing parameters`() {
        //todo similar
    }
//
//    @Test
//    fun `download and store`() {
//        // Insert an attachment into node zero's store directly.
//        val id = aliceNode.importAttachment(fakeAttachment("file1.txt", "Some useful content"))
//
//        // Get node one to run a flow to fetch it and insert it.
//        assert.that(
//                bobNode.startAttachmentFlow(id, alice),
//                willReturn(noAttachments()))
//
//        // Verify it was inserted into node one's store.
//        val attachment = bobNode.getAttachmentWithId(id)
//        assert.that(attachment, hashesTo(id))
//
//        // Shut down node zero and ensure node one can still resolve the attachment.
//        aliceNode.dispose()
//
//        assert.that(
//                bobNode.startAttachmentFlow(id, alice),
//                willReturn(soleAttachment(attachment)))
//    }
//
//    @Test
//    fun missing() {
//        val hash: SecureHash = SecureHash.randomSHA256()
//
//        // Get node one to fetch a non-existent attachment.
//        assert.that(
//                bobNode.startAttachmentFlow(hash, alice),
//                willThrow(withRequestedHash(hash)))
//    }
//
//    fun withRequestedHash(expected: SecureHash) = has(
//            "requested hash",
//            FetchDataFlow.HashNotFound::requested,
//            equalTo(expected))
//
//    @Test
//    fun maliciousResponse() {
//        // Make a node that doesn't do sanity checking at load time.
//        val badAliceNode = makeBadNode(ALICE_NAME)
//        val badAlice = badAliceNode.info.singleIdentity()
//
//        // Insert an attachment into node zero's store directly.
//        val attachment = fakeAttachment("file1.txt", "Some useful content")
//        val id = badAliceNode.importAttachment(attachment)
//
//        // Corrupt its store.
//        val corruptBytes = "arggghhhh".toByteArray()
//        System.arraycopy(corruptBytes, 0, attachment, 0, corruptBytes.size)
//
//        val corruptAttachment = NodeAttachmentService.DBAttachment(attId = id.toString(), content = attachment)
//        badAliceNode.updateAttachment(corruptAttachment)
//
//        // Get n1 to fetch the attachment. Should receive corrupted bytes.
//        assert.that(
//                bobNode.startAttachmentFlow(id, badAlice),
//                willThrow<FetchDataFlow.DownloadedVsRequestedDataMismatch>()
//        )
//    }

    @InitiatingFlow
    private class InitiatingFetchParametersFlow(val otherSide: Party, val hashes: Set<SecureHash>) : FlowLogic<FetchDataFlow.Result<SignedParametersByHash>>() {
        @Suspendable
        override fun call(): FetchDataFlow.Result<SignedParametersByHash> {
            val session = initiateFlow(otherSide)
            return subFlow(FetchNetworkParametersFlow(hashes, session))
        }
    }

    @InitiatedBy(InitiatingFetchParametersFlow::class)
    private class FetchParametersResponse(val otherSideSession: FlowSession) : FlowLogic<Void?>() {
        @Suspendable
        override fun call() = subFlow(TestNoSecurityDataVendingFlow(otherSideSession)) //todo
    }

    //TODO it repeats
    override fun makeNode(name: CordaX500Name) =
            mockNet.createPartyNode(makeUnique(name)).apply {
                registerInitiatedFlow(FetchParametersResponse::class.java)
            }

//    //region Operations
//    private fun TestStartedNode.importAttachment(attachment: ByteArray) =
//            attachments.importAttachment(attachment.inputStream(), "test", null)
//                    .andRunNetwork()
//
//    private fun TestStartedNode.updateAttachment(attachment:  NodeAttachmentService.DBAttachment) = database.transaction {
//        session.update(attachment)
//    }.andRunNetwork()
//
//    private fun TestStartedNode.startAttachmentFlow(hash: SecureHash, otherSide: Party) = startFlowAndRunNetwork(
//            InitiatingFetchAttachmentsFlow(otherSide, setOf(hash)))
//
//    private fun TestStartedNode.getAttachmentWithId(id: SecureHash) =
//            attachments.openAttachment(id)!!
//    //endregion
//
//    //region Matchers
//    private fun noAttachments() = has(FetchDataFlow.Result<Attachment>::fromDisk, isEmpty)
//    private fun soleAttachment(attachment: Attachment) = has(FetchDataFlow.Result<Attachment>::fromDisk,
//            hasSize(equalTo(1)) and
//                    hasElement(attachment))
//
//    private fun hashesTo(hash: SecureHash) = has<Attachment, SecureHash>(
//            "hash",
//            { it.open().hash() },
//            equalTo(hash))
//    //endregion
}