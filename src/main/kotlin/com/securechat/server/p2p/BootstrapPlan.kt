package com.securechat.server.p2p

/**
 * High-level P2P bootstrap / discovery abstractions for future decentralized SecureChat network.
 *
 * This file intentionally contains only interfaces and documentation.
 * No runtime wiring is done yet to avoid affecting the current server behavior.
 *
 * Goals:
 * - Allow us to introduce P2P-aware features in small, compatible steps.
 * - Keep existing auth/chat HTTP+WebSocket APIs stable.
 * - Make it clear where bootstrap, discovery and relay responsibilities live.
 */

/**
 * Logical identity of a SecureChat peer in the network.
 *
 * This is distinct from AuthUsers / Profiles:
 * - AuthUsers: server-side account used for the hosted relay / API.
 * - Profiles: human-oriented metadata (displayName, avatarUrl).
 * - PeerIdentity: cryptographic identity of a node/device in the P2P network.
 */
data class PeerIdentity(
    val peerId: String,           // stable identifier (e.g. hash of public key)
    val userId: String?,          // optional link to AuthUsers.userId when known
    val deviceId: String?,        // optional link to Devices.deviceId when known
    val publicKey: String,        // base64-encoded public key used for P2P auth
)

/**
 * Minimal information about how to reach a peer in a decentralized overlay.
 *
 * This deliberately avoids transport-specific details for now; concrete
 * implementations can extend it with multiaddr / WebRTC / QUIC metadata.
 */
data class PeerEndpoint(
    val peerId: String,
    val addresses: List<String>,  // e.g. obfuscated multiaddrs, TURN hints, rendezvous IDs
    val lastSeenAt: Long,
    val isRelayCapable: Boolean,
)

/**
 * Abstraction for a bootstrap / discovery service.
 *
 * The hosted SecureChat server can act as:
 * - A bootstrap node: initial contact point to discover overlay peers.
 * - A gossip / rendezvous coordinator for hidden peer graphs.
 *
 * These methods should be safe to expose over authenticated HTTP/WebSocket
 * without revealing the full peer graph to arbitrary clients.
 */
interface BootstrapService {

    /**
     * Register or refresh a peer's presence at the bootstrap node.
     *
     * Later we can:
     * - Bind this to authenticated userId/deviceId from JWT.
     * - Attach proof-of-identity signatures over the payload.
     */
    suspend fun registerPeer(
        identity: PeerIdentity,
        endpoint: PeerEndpoint,
    )

    /**
     * Return a limited view of potential peers for initial connections.
     *
     * The implementation should:
     * - Filter by user-level access control and privacy requirements.
     * - Avoid returning raw IP addresses for hidden peers when not desired.
     * - Potentially prioritize relay-capable peers.
     */
    suspend fun discoverPeers(
        requestingPeerId: String,
        limit: Int = 32,
    ): List<PeerEndpoint>
}

/**
 * Abstraction for identity verification in the P2P overlay.
 *
 * This is deliberately decoupled from JWT-based HTTP auth:
 * - JWT: authenticates to the hosted SecureChat server.
 * - PeerIdentityVerification: authenticates peers to each other.
 */
interface PeerIdentityVerificationService {

    /**
     * Verifies that a given PeerIdentity is consistent with:
     * - Stored public keys / device bindings.
     * - Optional signatures provided in future bootstrap requests.
     *
     * For now, this is only a contract; actual verification rules can evolve
     * without changing higher-level bootstrap and relay logic.
     */
    suspend fun verifyIdentity(identity: PeerIdentity): Boolean
}

/**
 * Abstraction for relay fallback coordination.
 *
 * The current SecureChat server already acts as a message relay via HTTP+WebSocket.
 * This service is a future-oriented facade to:
 * - Map P2P-level peerIds to server-level userId/deviceId.
 * - Decide when to fall back to server relay if direct P2P channels fail.
 *
 * IMPORTANT: No actual routing changes should depend on this yet;
 * existing chat delivery must continue to use the current ChatService + WebSocket layer.
 */
interface RelayCoordinationService {

    /**
     * Mark that two peers attempted (or failed) to establish a direct channel.
     *
     * Future use:
     * - Track connectivity quality.
     * - Decide when to strongly prefer server relay for a given pair.
     */
    suspend fun reportDirectAttempt(
        fromPeerId: String,
        toPeerId: String,
        succeeded: Boolean,
    )

    /**
     * Determine whether the server should proactively offer relay for a pair.
     *
     * Initial implementation can always return true for existing server-based
     * chats, keeping behavior identical to today while providing a P2P-aware hook.
     */
    suspend fun shouldUseRelay(
        fromPeerId: String,
        toPeerId: String,
    ): Boolean
}

/**
 * Entry point for wiring bootstrap / discovery into the existing HTTP/WebSocket layer.
 *
 * Future steps (not implemented yet):
 * - Add authenticated routes such as:
 *   - POST /p2p/bootstrap/register
 *   - GET  /p2p/bootstrap/peers
 * - Translate between:
 *   - AuthUsers.userId / Devices.deviceId
 *   - PeerIdentity.peerId / publicKey
 * - Use ChatService contact graph as an input to BootstrapService policies,
 *   without leaking exact chat topology.
 *
 * Keeping this contract here ensures that when we add those routes, they will
 * sit alongside existing auth/chat/backup routes without changing their behavior.
 */
interface BootstrapModule {
    val bootstrapService: BootstrapService
    val identityVerification: PeerIdentityVerificationService
    val relayCoordination: RelayCoordinationService
}

