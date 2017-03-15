package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Party
import net.corda.core.crypto.composite
import net.corda.core.flows.FlowLogic
import net.corda.core.node.PluginServiceHub
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.security.cert.Certificate

/**
 * Flow for requesting a transaction key from a counterparty. Normally would be called as a subflow rather than
 * directly, sharing the same session from the calling flow so that the counterparty knows to trust
 */
object TxKeyFlow {
    fun registerFlowInitiator(services: PluginServiceHub) {
        services.registerFlowInitiator(Requester::class.java, ::Provide)
    }

    class Requester(val otherSide: Party,
                    val data: Long,
                    override val progressTracker: ProgressTracker): FlowLogic<Pair<CompositeKey, Certificate?>>() {
        constructor(otherSide: Party, data: Long) : this(otherSide, data, tracker())

        companion object {
            object REQUESTING_KEY : ProgressTracker.Step("Requesting key")
            object VERIFYING_CERTIFICATE : ProgressTracker.Step("Verifying certificate")

            fun tracker() = ProgressTracker(REQUESTING_KEY, VERIFYING_CERTIFICATE)
        }

        @Suspendable
        override fun call(): Pair<CompositeKey, Certificate?> {
            progressTracker.currentStep = REQUESTING_KEY
            val untrustedKey = sendAndReceive<Response>(otherSide, Request(data))
            progressTracker.currentStep = VERIFYING_CERTIFICATE
            return untrustedKey.unwrap {
                // TODO: Verify the certificate, once we have certificates
                Pair(it.key, it.certificate)
            }
        }
    }

    /**
     * Flow which waits for a key request from a counterparty, generates a new key and then returns it to the
     * counterparty and as the result from the flow.
     */
    class Provide(val otherSide: Party,
                  override val progressTracker: ProgressTracker): FlowLogic<CompositeKey>() {
        constructor(otherSide: Party) : this(otherSide, tracker())

        companion object {
            object AWAIITNG_REQUEST : ProgressTracker.Step("Awaiting request")
            object GENERATING_KEY : ProgressTracker.Step("Generating key")
            object GENERATING_CERTIFICATE : ProgressTracker.Step("Generating certificate")

            fun tracker() = ProgressTracker(AWAIITNG_REQUEST, GENERATING_KEY, GENERATING_CERTIFICATE)
        }

        @Suspendable
        override fun call(): CompositeKey {
            progressTracker.currentStep == AWAIITNG_REQUEST
            val request = receive<Request>(otherSide).unwrap {
                serviceHub.keyManagementService.verifyKeyRequest(otherSide, it.data)
                it
            }
            progressTracker.currentStep == GENERATING_KEY
            val key = serviceHub.keyManagementService.freshKey().public.composite
            progressTracker.currentStep == GENERATING_CERTIFICATE
            // TODO: Generate and sign certificate for the key, once we have signing support for composite keys
            // (in this case the legal identity key)
            send(otherSide, Response(key, null))
            return key
        }
    }

    @CordaSerializable
    data class Request(val data: Long)
    @CordaSerializable
    data class Response(val key: CompositeKey, val certificate: Certificate?)
}