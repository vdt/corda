package net.corda.core.flows

import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Party
import net.corda.flows.TxKeyFlow
import net.corda.core.random63BitValue
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.testing.ALICE
import net.corda.testing.BOB
import net.corda.testing.MOCK_IDENTITY_SERVICE
import net.corda.testing.ledger
import net.corda.testing.node.MockNetwork
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.TemporalAmount
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Created by rossnicoll on 16/03/2017.
 */
class TxKeyFlowTests {
    lateinit var net: MockNetwork

    @Before
    fun before() {
        net = MockNetwork(false)
        net.identities += MOCK_IDENTITY_SERVICE.identities
    }

    @Test
    fun `request key`() {
        // We run this in parallel threads to help catch any race conditions that may exist.
        net = MockNetwork(false, true)

        ledger {
            // Set up values we'll need
            val notaryNode = net.createNotaryNode(null, DUMMY_NOTARY.name)
            val aliceNode = net.createPartyNode(notaryNode.info.address, ALICE.name)
            val bobNode = net.createPartyNode(notaryNode.info.address, BOB.name)
            val aliceKey: Party = aliceNode.services.myInfo.legalIdentity
            val bobKey: Party = bobNode.services.myInfo.legalIdentity
            val nonce: Long = random63BitValue()

            // Run the flows
            bobNode.services.keyManagementService.expectKeyRequest(aliceKey, nonce, Duration.ofMinutes(1))
            val requesterFlow = aliceNode.services.startFlow(TxKeyFlow.Requester(bobKey, nonce))

            // Get the results
            // val expected: CompositeKey = provideFlow.resultFuture.get()
            val actual: CompositeKey = requesterFlow.resultFuture.get().first
            assertNotNull(actual)
        }
    }
}