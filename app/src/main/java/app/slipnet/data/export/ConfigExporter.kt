package app.slipnet.data.export

import android.util.Base64
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.SshAuthType
import app.slipnet.domain.model.TunnelType
import app.slipnet.util.LockPasswordUtil
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports profiles to compact encoded text format.
 *
 * Single profile format: slipnet://[base64-encoded-profile]
 * Multiple profiles: one URI per line
 *
 * Encoded profile format v15 (pipe-delimited):
 * v15|tunnelType|name|domain|resolvers|authMode|keepAlive|cc|port|host|gso|dnsttPublicKey|socksUsername|socksPassword|sshEnabled|sshUsername|sshPassword|sshPort|forwardDnsThroughSsh|sshHost|useServerDns(removed)|dohUrl(removed)|dnsTransport|sshAuthType|sshPrivateKey(b64)|sshKeyPassphrase(b64)|torBridgeLines(removed)|dnsttAuthoritative|naivePort(removed)|naiveUsername(removed)|naivePassword(removed)|isLocked|lockPasswordHash
 *
 * Resolvers format (comma-separated): host:port:auth,host:port:auth
 */
@Singleton
class ConfigExporter @Inject constructor() {

    companion object {
        const val SCHEME = "slipnet://"
        const val ENCRYPTED_SCHEME = "slipnet-enc://"
        const val VERSION = "15"
        const val MODE_SLIPSTREAM = "ss"
        const val MODE_SLIPSTREAM_SSH = "slipstream_ssh"
        const val MODE_DNSTT = "dnstt"
        const val MODE_DNSTT_SSH = "dnstt_ssh"
        private const val FIELD_DELIMITER = "|"
        private const val RESOLVER_DELIMITER = ","
        private const val RESOLVER_PART_DELIMITER = ":"
    }

    fun exportSingleProfile(profile: ServerProfile): String {
        if (profile.isLocked) throw IllegalStateException("Cannot export a locked profile")
        return encodeProfile(profile)
    }

    fun exportSingleProfileLocked(profile: ServerProfile, password: String): String {
        val hash = LockPasswordUtil.hashPassword(password)
        val lockedProfile = profile.copy(isLocked = true, lockPasswordHash = hash)
        val data = buildProfileData(lockedProfile)
        val encrypted = LockPasswordUtil.encryptConfig(data)
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$ENCRYPTED_SCHEME$encoded"
    }

    fun exportAllProfiles(profiles: List<ServerProfile>): String {
        val exportable = profiles.filter { !it.isLocked }
        return exportable.joinToString("\n") { encodeProfile(it) }
    }

    private fun buildProfileData(profile: ServerProfile): String {
        val resolversStr = profile.resolvers.joinToString(RESOLVER_DELIMITER) { resolver ->
            "${resolver.host}${RESOLVER_PART_DELIMITER}${resolver.port}${RESOLVER_PART_DELIMITER}${if (resolver.authoritative) "1" else "0"}"
        }

        val tunnelTypeStr = when (profile.tunnelType) {
            TunnelType.SLIPSTREAM -> MODE_SLIPSTREAM
            TunnelType.SLIPSTREAM_SSH -> MODE_SLIPSTREAM_SSH
            TunnelType.DNSTT -> MODE_DNSTT
            TunnelType.DNSTT_SSH -> MODE_DNSTT_SSH
        }

        return listOf(
            VERSION,
            tunnelTypeStr,
            profile.name,
            profile.domain,
            resolversStr,
            if (profile.authoritativeMode) "1" else "0",
            profile.keepAliveInterval.toString(),
            profile.congestionControl.value,
            profile.tcpListenPort.toString(),
            profile.tcpListenHost,
            if (profile.gsoEnabled) "1" else "0",
            profile.dnsttPublicKey,
            profile.socksUsername ?: "",
            profile.socksPassword ?: "",
            if (profile.tunnelType == TunnelType.DNSTT_SSH || profile.tunnelType == TunnelType.SLIPSTREAM_SSH) "1" else "0",
            profile.sshUsername,
            profile.sshPassword,
            profile.sshPort.toString(),
            "0",
            profile.sshHost,
            "0", // position 20: was useServerDns (removed)
            "", // position 21: was dohUrl (removed)
            profile.dnsTransport.value,
            profile.sshAuthType.value,
            Base64.encodeToString(profile.sshPrivateKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            Base64.encodeToString(profile.sshKeyPassphrase.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            "", // position 26: was torBridgeLines (removed)
            if (profile.dnsttAuthoritative) "1" else "0",
            "0", // position 28: was naivePort (removed)
            "", // position 29: was naiveUsername (removed)
            "", // position 30: was naivePassword (removed)
            if (profile.isLocked) "1" else "0",
            profile.lockPasswordHash
        ).joinToString(FIELD_DELIMITER)
    }

    private fun encodeProfile(profile: ServerProfile): String {
        val data = buildProfileData(profile)
        val encoded = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "$SCHEME$encoded"
    }
}
