package net.corda.node.services.keys

import net.corda.core.ThreadBox
import net.corda.core.crypto.Party
import net.corda.core.crypto.generateKeyPair
import net.corda.core.node.PluginServiceHub
import net.corda.core.node.services.KeyManagementService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.flows.TxKeyFlow
import net.corda.node.utilities.*
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.statements.InsertStatement
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Instant
import java.util.*

/**
 * A persistent re-implementation of [E2ETestKeyManagementService] to support node re-start.
 *
 * This is not the long-term implementation.  See the list of items in the above class.
 *
 * This class needs database transactions to be in-flight during method calls and init.
 */
class PersistentKeyManagementService(services: PluginServiceHub, initialKeys: Set<KeyPair>) : SingletonSerializeAsToken(), KeyManagementService {

    private object Table : JDBCHashedTable("${NODE_DATABASE_PREFIX}our_key_pairs") {
        val publicKey = publicKey("public_key")
        val privateKey = blob("private_key")
    }
    private object RequestTable : JDBCHashedTable("${NODE_DATABASE_PREFIX}our_expected_key_reqs") {
        val party = varchar("other_party_name", 120)
        val nonce = long("nonce")
        val timeout = instant("timeout")
    }

    private class InnerState {
        val keys = object : AbstractJDBCHashMap<PublicKey, PrivateKey, Table>(Table, loadOnInit = false) {
            override fun keyFromRow(row: ResultRow): PublicKey = row[table.publicKey]

            override fun valueFromRow(row: ResultRow): PrivateKey = deserializeFromBlob(row[table.privateKey])

            override fun addKeyToInsert(insert: InsertStatement, entry: Map.Entry<PublicKey, PrivateKey>, finalizables: MutableList<() -> Unit>) {
                insert[table.publicKey] = entry.key
            }

            override fun addValueToInsert(insert: InsertStatement, entry: Map.Entry<PublicKey, PrivateKey>, finalizables: MutableList<() -> Unit>) {
                insert[table.privateKey] = serializeToBlob(entry.value, finalizables)
            }
        }
        val expectedRequests = object : AbstractJDBCHashMap<Pair<String, Long>, Instant, RequestTable>(RequestTable, loadOnInit = false) {
            override fun addKeyToInsert(insert: InsertStatement, entry: Map.Entry<Pair<String, Long>, Instant>, finalizables: MutableList<() -> Unit>) {
                insert[table.party] = entry.key.first
                insert[table.nonce] = entry.key.second
            }

            override fun addValueToInsert(insert: InsertStatement, entry: Map.Entry<Pair<String, Long>, Instant>, finalizables: MutableList<() -> Unit>) {
                insert[table.timeout] = entry.value
            }

            override fun keyFromRow(row: ResultRow): Pair<String, Long> {
                return Pair(row[table.party], row[table.nonce])
            }

            override fun valueFromRow(row: ResultRow): Instant {
                return row[table.timeout]
            }
        }
    }

    private val mutex = ThreadBox(InnerState())

    init {
        TxKeyFlow.registerFlowInitiator(services)
        mutex.locked {
            keys.putAll(initialKeys.associate { Pair(it.public, it.private) })
        }
    }

    override val keys: Map<PublicKey, PrivateKey> get() = mutex.locked { HashMap(keys) }

    override fun expectKeyRequest(otherSide: Party, nonce: Long, timeout: Instant) {
        require(timeout.isAfter(Instant.now()))
        mutex.locked {
            expectedRequests[Pair(otherSide.name, nonce)] = timeout
        }
    }

    override fun verifyKeyRequest(otherSide: Party, nonce: Long) {
        val timeout = mutex.locked {
            expectedRequests.remove(Pair(otherSide.name, nonce))
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
