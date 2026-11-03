package app.ihearyou.marmot

import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaFileUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
import dev.ipf.marmotkit.SecretStore
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * I Hear You's own White Noise (Marmot) identity, run through MarmotKit: it is invited into a
 * group like any contact, sends voice notes there, and hands incoming messages to the reply
 * speaker. One runtime per process (MDK owns its root directory), opened only when the user
 * enables the White Noise destination.
 */
object MarmotHost {

    /** Public relays for the relay lists and key package; White Noise users reach us through these. */
    /**
     * White Noise's own relays come first: White Noise looks people up there, and an identity
     * whose relay lists and key packages are only on the public relays cannot be invited
     * ("couldn't find a usable chat key"). The public ones keep it reachable for other clients.
     */
    val RELAYS = listOf(
        "wss://relay.eu.whitenoise.chat",
        "wss://relay.us.whitenoise.chat",
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://nos.lol",
    )

    data class Group(val id: String, val name: String, val pending: Boolean, val member: Boolean)

    /** A message from someone else in a joined group. */
    data class Incoming(
        val groupId: String,
        val messageId: String,
        val sender: String,
        val text: String,
        val media: List<MediaAttachmentReferenceFfi>,
        val at: Long,
    )

    enum class Phase { OFF, STARTING, READY, FAILED }

    val phase = MutableStateFlow(Phase.OFF)
    val error = MutableStateFlow<String?>(null)
    val npub = MutableStateFlow<String?>(null)
    val groups = MutableStateFlow<List<Group>>(emptyList())

    @Volatile var onIncoming: ((Incoming) -> Unit)? = null
    @Volatile var log: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var marmot: Marmot? = null
    private var account: String? = null
    private var accountHex: String? = null
    private var events: Job? = null
    private var nativeReady = false

    /**
     * Not cancellable: a caller's scope going away mid-start must not leave a runtime open but
     * unreferenced; it would hold MDK's root and every later start would fail with RuntimeBusy.
     */
    suspend fun start(context: Context) = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            if (marmot != null) return@withLock
            phase.value = Phase.STARTING
            error.value = null
            var opened: Marmot? = null
            try {
                if (!nativeReady) {
                    MarmotAndroid.initialize(context.applicationContext)
                    nativeReady = true
                }
                val root = File(context.noBackupFilesDir, "marmot").apply { mkdirs() }
                val kv = SecureKv(context)
                val options = MarmotOptions(
                    clientName = "i-hear-you-android",
                    secretStore = KvSecretStore(kv),
                    // White Noise's preset: its media servers, recovery relays and key-package
                    // preferences, so groups made in White Noise work here unchanged
                    deployment = marmotDeploymentWhiteNoise(),
                )
                var attempt = 0
                while (opened == null) {
                    try {
                        opened = Marmot.newWithConfiguration(root.path, RELAYS, options)
                    } catch (e: MarmotKitException.RuntimeBusy) {
                        // a previous runtime of this process is still closing its root
                        if (++attempt > 20) throw e
                        delay(250)
                    }
                }
                val m = opened!!
                m.start()
                marmot = m
                val acct = ensureAccount(m, app.ihearyou.voice.VoiceSettings(context).whiteNoiseName ?: DEFAULT_NAME)
                account = acct.label
                accountHex = acct.accountIdHex
                npub.value = m.npub(acct.accountIdHex)
                profileName.value = runCatching { m.userProfile(acct.accountIdHex) }.getOrNull()?.let { it.displayName ?: it.name }
                refreshGroups()
                events = scope.launch { pump(m) }
                phase.value = Phase.READY
                log("white noise: ready as ${npub.value}")
                val label = acct.label
                scope.launch {
                    ensureWhiteNoiseRelays(m, label)
                    ensureKeyPackage(m, label)
                }
            } catch (t: Throwable) {
                phase.value = Phase.FAILED
                // some MarmotKit errors carry an empty message or only ids: name the error instead
                error.value = readable(t)
                log("white noise: start failed: ${error.value}")
                // close whatever was opened, also one that never became `marmot`
                (marmot ?: opened)?.let { r ->
                    runCatching { r.shutdownAndClose() }
                    runCatching { r.close() }
                }
                marmot = null
                events?.cancel()
                events = null
            }
        }
    }

    /**
     * Identities made before White Noise's relays were in [RELAYS] published their relay lists and
     * key package only to the public relays: publish the lists again, and a key package where
     * White Noise looks for it.
     */
    private suspend fun ensureWhiteNoiseRelays(m: Marmot, label: String) {
        runCatching {
            val lists = m.accountRelayLists(label)
            if (!lists.nip65.relays.toSet().containsAll(RELAYS)) {
                log("white noise: publishing relay lists with White Noise's relays")
                m.publishRelayLists(label, RELAYS, RELAYS, RELAYS)
                m.rotateKeyPackage(label)
            }
        }.onFailure { log("white noise: relay list update failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * Others can invite this identity only when a key package of its npub is on the relays. One
     * whose first start broke off after the identity was made (or whose package expired) has none,
     * and White Noise then cannot add it. Publish one when the relays show none.
     */
    private suspend fun ensureKeyPackage(m: Marmot, label: String) {
        runCatching {
            val onRelays = m.accountKeyPackages(label, RELAYS)
            log("white noise: key packages on the relays: ${onRelays.size}")
            if (onRelays.isEmpty()) m.publishNewKeyPackage(label)
            m.runDueMaintenance(label)
        }.onFailure { log("white noise: key package check failed: ${readable(it)}") }
    }

    /** The name a new identity starts with; the Voice screen can change it. */
    const val DEFAULT_NAME = "I Hear You"
    private const val ABOUT = "Voice notes from hearing aids"

    /** The display name others see (kind:0), as last read or published. */
    val profileName = MutableStateFlow<String?>(null)

    /**
     * Publishes a new display name (kind:0), keeping the rest of the profile. Group members see it
     * from then on; their apps may take a while to fetch it.
     */
    suspend fun rename(name: String) = io { m, acct ->
        val n = name.trim()
        require(n.isNotEmpty()) { "empty name" }
        val hex = accountHex ?: error("no White Noise identity")
        val current = runCatching { m.userProfile(hex) }.getOrNull()
            ?: UserProfileMetadataFfi(null, null, ABOUT, null, null, null, null)
        val published = m.publishUserProfileUsingAccountRelays(acct, current.copy(name = n, displayName = n))
        profileName.value = published.displayName ?: published.name ?: n
    }

    /**
     * A MarmotKit error in words for logs and notes. Many carry only the group id as their message
     * ("groupIdHex=…") or nothing at all; those give the error's name ("Group Removed").
     */
    fun readable(t: Throwable): String {
        val m = t.message?.trim().orEmpty()
        val idsOnly = m.isEmpty() || Regex("""^(\w+=\S*,?\s*)+$""").matches(m)
        return if (idsOnly) t.javaClass.simpleName.replace(Regex("([a-z])([A-Z])"), "$1 $2") else m
    }

    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            val m = marmot ?: return@withLock
            marmot = null
            events?.cancel()
            events = null
            runCatching { m.shutdownAndClose() }
            runCatching { m.close() }
            phase.value = Phase.OFF
        }
    }

    /** The identity's private key, for a backup the user keeps; plain nsec, no passphrase. */
    fun revealNsec(): String {
        val m = marmot ?: error("White Noise is not running")
        return m.revealNsec(account ?: error("no White Noise identity"))
    }

    /**
     * Replaces this app's identity with [nsec] (a backup, or a key made elsewhere). Group
     * membership is MLS state, not part of the key: the old identity's groups are dropped, and
     * groups have to invite the npub again.
     */
    suspend fun importNsec(context: Context, nsec: String) = withContext(Dispatchers.IO) {
        val key = nsec.trim()
        require(key.startsWith("nsec1")) { "not an nsec" }
        lock.withLock {
            val m = marmot ?: error("White Noise is not running")
            val old = m.listAccounts()
            val acct = m.login(key, RELAYS, RELAYS, RELAYS)
            for (a in old) if (a.accountIdHex != acct.accountIdHex) runCatching { m.removeAccount(a.label) }
            log("white noise: identity replaced by import")
        }
        // reopen on the new identity: fresh account refs, groups and event stream
        stop()
        start(context)
    }

    private suspend fun ensureAccount(m: Marmot, name: String): AccountSummaryFfi {
        val existing = m.listAccounts().firstOrNull()
        return when {
            existing == null -> {
                log("white noise: creating identity")
                // publishes the relay lists (10002, 10050 inbox), a profile (so groups see a name,
                // not a random pseudonym) and the first key package
                val profile = UserProfileMetadataFfi(name, name, ABOUT, null, null, null, null)
                runCatching { m.createIdentityWithInitialProfile(profile, RELAYS, RELAYS, RELAYS).account }
                    .getOrElse { m.createIdentity(RELAYS, RELAYS, RELAYS) }
            }
            existing.signedOut -> m.signInAccount(existing.label)
            else -> existing
        }
    }

    private suspend fun pump(m: Marmot) {
        val sub = m.subscribeEvents()
        try {
            while (scope.isActive) {
                when (val e = sub.next() ?: break) {
                    is MarmotEventFfi.MessageReceived -> onMessage(m, e.received.message)
                    is MarmotEventFfi.GroupJoined, is MarmotEventFfi.GroupStateUpdated,
                    is MarmotEventFfi.WelcomeDeliveryPending -> refreshGroups()
                    is MarmotEventFfi.AccountError -> log("white noise: account error")
                    else -> {}
                }
            }
        } catch (t: Throwable) {
            log("white noise: event stream ended: ${t.javaClass.simpleName}")
        } finally {
            runCatching { sub.close() }
        }
    }

    private suspend fun onMessage(m: Marmot, msg: dev.ipf.marmotkit.ReceivedMessageFfi) {
        val self = accountHex ?: return
        if (msg.sender == self || msg.kind != 9uL) return
        val acct = account ?: return
        // media references come resolved on the timeline record, not on the raw message
        val media = runCatching {
            m.timelineMessages(acct, TimelineMessageQueryFfi(msg.groupIdHex, null, null, null, null, null, 30u))
                .messages.firstOrNull { it.messageIdHex == msg.messageIdHex }
                ?.media?.filterIsInstance<MediaAttachmentOutcomeFfi.Accepted>()?.map { it.reference }
        }.getOrNull().orEmpty()
        val at = msg.recordedAt.toLong().let { if (it > 100_000_000_000L) it else it * 1000 }
        onIncoming?.invoke(Incoming(msg.groupIdHex, msg.messageIdHex, msg.sender, msg.plaintext, media, at))
    }

    fun refreshGroups() {
        val m = marmot ?: return
        val acct = account ?: return
        val next = runCatching {
            m.chatList(acct, false).map {
                Group(it.groupIdHex, it.groupName.ifBlank { it.title }, it.pendingConfirmation, it.selfMembership == SelfMembershipFfi.MEMBER)
            }
        }.getOrDefault(groups.value)
        if (next != groups.value) {
            log("white noise: groups " + next.joinToString { "${it.name} ${it.id.take(8)}${if (it.pending) " (invite)" else ""}" })
        }
        groups.value = next
    }

    suspend fun accept(groupId: String) = io { m, acct ->
        m.acceptGroupInvite(acct, groupId)
        refreshGroups()
    }

    suspend fun decline(groupId: String) = io { m, acct ->
        m.declineGroupInvite(acct, groupId)
        refreshGroups()
    }

    /** Uploads (encrypted, to the group's media servers) and sends in one message. */
    suspend fun sendVoice(groupId: String, file: File, fileName: String, mime: String, caption: String?) = io { m, acct ->
        val request = MediaFileUploadRequestFfi(
            attachments = listOf(MediaFileUploadAttachmentRequestFfi(file.path, file.length().toULong(), fileName, mime, null, null)),
            caption = caption,
            send = true,
            blossomServer = null,
        )
        val result = m.uploadMediaFiles(acct, groupId, request, MediaFileTransferControlFfi())
        if (result.sent == null) error("uploaded but not sent")
    }

    suspend fun sendText(groupId: String, text: String) = io { m, acct ->
        m.sendText(acct, groupId, text)
    }

    suspend fun download(groupId: String, ref: MediaAttachmentReferenceFfi): ByteArray = io { m, acct ->
        m.downloadMedia(acct, groupId, ref).plaintext
    }

    private suspend fun <T> io(block: suspend (Marmot, String) -> T): T = withContext(Dispatchers.IO) {
        val m = marmot ?: error("White Noise is not running")
        val acct = account ?: error("no White Noise identity")
        block(m, acct)
    }

    private class KvSecretStore(private val kv: SecureKv) : SecretStore {
        private fun name(account: String) = "mk:$account"
        override fun hasSecretForLabel(label: String): Boolean = kv.get("mklabel:$label") != null
        override fun hasSecretForAccountId(accountIdHex: String): Boolean = kv.get(name(accountIdHex)) != null
        override fun writeSecret(label: String, accountIdHex: String, secretKeyHex: String) {
            kv.put(name(accountIdHex), secretKeyHex)
            kv.put("mklabel:$label", accountIdHex)
        }
        override fun loadSecret(label: String, accountIdHex: String): String =
            kv.get(name(accountIdHex)) ?: throw MarmotKitException.SecretNotFound("no secret for this account")
        override fun removeSecret(label: String, accountIdHex: String) {
            kv.put(name(accountIdHex), null)
            kv.put("mklabel:$label", null)
        }
    }
}
