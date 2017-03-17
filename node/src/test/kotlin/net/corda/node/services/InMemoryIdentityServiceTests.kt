package net.corda.node.services

import net.corda.core.crypto.Party
import net.corda.core.crypto.composite
import net.corda.core.crypto.generateKeyPair
import net.corda.node.services.identity.InMemoryIdentityService
import net.corda.testing.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the in memory identity service.
 */
class InMemoryIdentityServiceTests {

    @Test
    fun `get all identities`() {
        val service = InMemoryIdentityService()
        assertNull(service.getAllIdentities().firstOrNull())
        service.registerIdentity(ALICE)
        var expected = setOf(ALICE)
        var actual = service.getAllIdentities().toHashSet()
        assertEquals(expected, actual)

        // Add a second party and check we get both back
        service.registerIdentity(BOB)
        expected = setOf(ALICE, BOB)
        actual = service.getAllIdentities().toHashSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `get identity by key`() {
        val service = InMemoryIdentityService()
        assertNull(service.partyFromKey(ALICE_PUBKEY))
        service.registerIdentity(ALICE)
        assertEquals(ALICE, service.partyFromKey(ALICE_PUBKEY))
        assertNull(service.partyFromKey(BOB_PUBKEY))
    }

    @Test
    fun `get identity by name with empty`() {
        val service = InMemoryIdentityService()
        assertNull(service.partyFromName(ALICE.name))
    }

    @Test
    fun `get identity by name`() {
        val service = InMemoryIdentityService()
        val node = listOf("Node A", "Node B", "Node C").map { Party(it, generateKeyPair().public.composite) }
        assertNull(service.partyFromName(node.first().name))
        node.forEach { service.registerIdentity(it) }
        node.forEach { assertEquals(it, service.partyFromName(it.name)) }
    }
}