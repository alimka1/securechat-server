package com.securechat.server.trust

import com.securechat.server.p2p.PeerIdentity

/**
 * High-level interfaces for a future blockchain-based trust layer.
 *
 * IMPORTANT:
 * - This layer is ONLY for public identity key registry and trust verification.
 * - Chat messages, backups, and signaling stay off-chain.
 * - No concrete blockchain client or on-chain contract code is defined here.
 *
 * The goal is to define minimal, stable interfaces that can be implemented
 * against different chains or rollups without changing the rest of the server.
 */

/**
 * Representation of an identity record as anchored in a blockchain registry.
 *
 * This should be derived from an on-chain contract call in a real implementation,
 * but for now it is just a neutral data shape.
 */
data class OnChainIdentityRecord(
    val peerId: String,              // matches PeerIdentity.peerId
    val publicKey: String,           // base64 / hex public key as recorded on-chain
    val ownerAccount: String,        // chain account / address that controls this identity
    val revoked: Boolean,
    val lastUpdatedBlock: Long,
)

/**
 * Contract-level abstraction for a blockchain identity registry.
 *
 * In a real implementation, methods here would be thin wrappers over:
 * - ABI-encoded contract calls, or
 * - direct light-client verified state queries.
 *
 * Keeping this interface small allows us to:
 * - Swap underlying blockchain stacks.
 * - Add local caching / verification without exposing those details.
 */
interface IdentityRegistryContract {

    /**
     * Look up an identity record by peerId.
     *
     * Returns null if the registry has no entry for this peer.
     */
    suspend fun getIdentity(peerId: String): OnChainIdentityRecord?

    /**
     * Optional future extension: resolve by chain-level owner account.
     * Not used today, but included as a hook for account-based discovery.
     */
    suspend fun listIdentitiesByOwner(ownerAccount: String): List<OnChainIdentityRecord> =
        emptyList()
}

/**
 * Trust manager responsible for interpreting on-chain identity information
 * in the context of SecureChat's existing auth and P2P identity model.
 *
 * This is the main integration point between:
 * - JWT-based server auth (AuthUsers / Devices).
 * - P2P identities (PeerIdentity from the p2p package).
 * - Future blockchain identity registry (IdentityRegistryContract).
 */
interface TrustManager {

    /**
     * Verify that a given PeerIdentity is:
     * - Present in the blockchain registry (if required by policy).
     * - Not revoked.
     * - Cryptographically consistent with the on-chain public key.
     *
     * The exact cryptographic checks (e.g. signatures over nonces) are left
     * to a future implementation; this interface only encodes the intent.
     */
    suspend fun verifyPeerTrust(identity: PeerIdentity): TrustVerdict

    /**
     * Verify that a server-side account (userId) is consistently bound to
     * a given on-chain identity.
     *
     * Future integration points:
     * - Called during sensitive operations such as:
     *   - publishing prekeys,
     *   - registering devices,
     *   - joining P2P bootstrap / discovery.
     */
    suspend fun verifyAccountBinding(
        userId: String,
        peerId: String,
    ): TrustVerdict
}

/**
 * Result of a trust verification decision.
 *
 * This is intentionally richer than just Boolean to allow:
 * - graceful degradation when the chain is unavailable,
+ * - soft warnings vs. hard failures,
 * - detailed telemetry for SOC / monitoring.
 */
data class TrustVerdict(
    val trusted: Boolean,
    val reason: String,
    val source: TrustSource,
)

enum class TrustSource {
    /**
     * Trust based entirely on the local server database (e.g. AuthUsers / Devices).
     * This is effectively the current behavior before blockchain integration.
     */
    LOCAL_ONLY,

    /**
     * Trust established by cross-checking with the blockchain identity registry.
     */
    BLOCKCHAIN_VERIFIED,

    /**
     * No definitive conclusion could be reached (e.g. chain unavailable).
     * Call sites may choose to proceed with degraded trust or fail closed.
     */
    UNKNOWN,
}

/**
 * Planned integration points (documentation only, no runtime wiring yet):
 *
 * - Auth:
 *   - After a user logs in or registers, we can optionally:
 *     - Resolve an associated peerId for their account.
 *     - Use TrustManager.verifyAccountBinding(userId, peerId) to decide whether
 *       to mark the session as "blockchain-verified" for higher assurance flows.
 *
 * - Identity keys (prekeys, devices):
 *   - When handling PublishPreKeyRequest in Routing.kt, before persisting new
 *     device/identity keys, we can:
 *     - Construct a PeerIdentity from userId + deviceId + public key.
 *     - Call TrustManager.verifyPeerTrust(identity) to ensure it is consistent
 *       with the blockchain registry policy (if any).
 *
 * - Peer discovery / bootstrap:
 *   - In the future BootstrapService (p2p package), when returning candidate
 *     peers, we can:
 *     - Filter or annotate endpoints based on TrustVerdict.
 *     - Prefer BLOCKCHAIN_VERIFIED peers when the client requests a higher
 *       security level.
 *
 * These comments exist so that future implementers know exactly where and how
 * to plug blockchain-based trust into the existing architecture, without
 * changing current HTTP/WebSocket behavior today.
 */

