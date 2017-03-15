package net.corda.node.services.keys

import net.corda.core.ThreadBox
import net.corda.core.crypto.Party
import net.corda.core.crypto.generateKeyPair
import net.corda.core.node.PluginServiceHub
import net.corda.core.node.services.KeyManagementService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.flows.TxKeyFlow
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Instant
import java.util.*
import javax.annotation.concurrent.ThreadSafe

/**
 * A simple in-memory KMS that doesn't bother saving keys to disk. A real implementation would:
 *
 * - Probably be accessed via the network layer as an internal node service i.e. via a message queue, so it can run
 *   on a separate/firewalled service.
 * - Use the flow framework so requests to fetch keys can be suspended whilst a human signs off on the request.
 * - Use deterministic key derivation.
 * - Possibly have some sort of TREZOR-like two-factor authentication ability.
 *
 * etc.
 */
@ThreadSafe
class E2ETestKeyManagementService(services: PluginServiceHub, initialKeys: Set<KeyPair>) : SingletonSerializeAsToken(), KeyManagementService {
    private class InnerState {
        val keys = HashMap<PublicKey, PrivateKey>()
        val expectedRequests = HashMap<Pair<Party, Long>, Instant>()
    }

    private val mutex = ThreadBox(InnerState())

    init {
        TxKeyFlow.registerFlowInitiator(services)
        mutex.locked {
            for (key in initialKeys) {
                keys[key.public] = key.private
            }
        }
    }

    // Accessing this map clones it.
    override val keys: Map<PublicKey, PrivateKey> get() = mutex.locked { HashMap(keys) }

    override fun expectKeyRequest(otherSide: Party, nonce: Long, timeout: Instant) {
        require(timeout.isAfter(Instant.now()))
        mutex.locked {
            expectedRequests[Pair(otherSide, nonce)] = timeout
        }
    }

    override fun verifyKeyRequest(otherSide: Party, nonce: Long) {
        val timeout = mutex.locked {
            expectedRequests.remove(Pair(otherSide, nonce))
        }
        // TODO: Should have a background timer task that sweeps this and removes out of date registrations
        if (timeout == null || timeout.isBefore(Instant.now())) {
            throw IllegalArgumentException("No registered key requests for that party/data combination")
        }
    }

    override fun freshKey(): KeyPair {
        val keyPair = generateKeyPair()
        mutex.locked {
            keys[keyPair.public] = keyPair.private
        }
        return keyPair
    }
}
