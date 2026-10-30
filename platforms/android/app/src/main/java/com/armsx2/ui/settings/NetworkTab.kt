package com.armsx2.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.config.Settings
import com.armsx2.config.Dev9HostMapping
import com.armsx2.i18n.str
import com.armsx2.ui.Colors
import com.armsx2.ui.InGameOverlay
import java.net.NetworkInterface

/**
 * DEV9 networking/HDD settings brought over from OG ARMSX2's SettingsActivity.
 *
 * Android's useful backend is PCSX2's socket backend. PCAP options are kept
 * visible for parity/debugging, but normal users should leave the API on
 * Sockets and the adapter on Auto. DEV9 is initialized at VM boot, so these
 * settings are persisted immediately and take effect on the next game/BIOS
 * launch.
 */
@Composable
fun NetworkTab(state: MutableState<Settings>) {
    val s = state.value
    val scroll = settingsScrollState()
    ControllerAutoScroll(scroll)
    val adapters = remember { enumerateAdapters() }
    val context = androidx.compose.ui.platform.LocalContext.current
    /** This device's own LAN IPv4s, shown to the host so it can read one out to the guests. */
    val localAddresses = remember { enumerateLocalIPv4() }
    /** Stable per-device guest id. The peer id decides the emulated console's MAC and IP, so two
     *  devices sharing one cannot connect — deriving it removes both the collision and the need to
     *  ask the user for a number they have no way to choose sensibly. */
    val derivedPeerId = remember { derivePeerId(context) }
    // "Local Link" is deliberately NOT offered here — the Network mode control below owns it, so
    // there is exactly one way into LAN play. Indices still line up with NetApi in Config.h.
    val apiValues = listOf("Unset", "PCAP Bridged", "PCAP Switched", "TAP", "Sockets")
    val apiLabels = listOf("Unset", "PCAP Br.", "PCAP Sw.", "TAP", "Sockets")
    val apiIndex = apiValues.indexOf(s.network.dev9EthApi).let { if (it >= 0) it else apiValues.lastIndex }
    // 0 Online / 1 Host / 2 Join — derived from the stored settings rather than kept as its own
    // field, so there is a single source of truth and no way for the two to disagree.
    val netMode = if (s.network.dev9EthApi != "Local Link") 0 else if (s.network.localLinkHost) 1 else 2

    fun apply(updated: Settings) = InGameOverlay.saveSettings(updated)

    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            str("network.dev9.description"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        HelpText(str("network.dev9.help"))

        // Carries a warning because an attached network adapter can stop a game seeing the pad at
        // all: GT4 with this on loops its attract FMV and ignores every button, on screen and on a
        // controller, with nothing to say why — the on-screen input display still shows the presses,
        // since that is drawn host-side.
        //
        // The mechanism is NOT established. An earlier version of this comment blamed the IOP being
        // busy with network modules; that does not survive the evidence, because the identical DEV9
        // code delivered working input in this same game at 069f8a44. Under investigation as a
        // regression somewhere in 069f8a44..94d2e3f6 — DEV9 itself is unchanged across that window,
        // so it is a trigger rather than the cause. Warn about the effect, do not assert a cause.
        ToggleRow(str("network.enableDev9Ethernet"), s.network.dev9EthEnable,
            description = str("network.enableDev9Ethernet.desc"), field = "dev9EthEnable") {
            val currentDevice = s.network.dev9EthDevice.ifEmpty { "Auto" }
            apply(
                s.copy(
                    network = s.network.copy(
                        dev9EthEnable = it,
                        dev9EthApi = s.network.dev9EthApi.ifEmpty { "Sockets" },
                        dev9EthDevice = currentDevice,
                    ),
                )
            )
        }
        SettingsDivider()
        // Network mode is the FIRST thing under the enable toggle, because "am I playing online or
        // on a LAN with the person next to me?" is the top-level question. LAN play used to be
        // buried as one entry in the Ethernet API picker, where nobody would ever find it.
        SegmentedRow(
            label = str("network.mode.label"),
            options = listOf(
                str("network.mode.online"),
                str("network.mode.host"),
                str("network.mode.join"),
            ),
            selectedIndex = netMode,
            description = str("network.mode.description"),
            onChange = { mode ->
                // A room code outside 4-12 chars makes LocalLinkAdapter's constructor bail, which
                // deletes the adapter and clears EthEnable — so the GAME reports "network adapter
                // not connected" and nothing points at the room code. It defaulted to empty, so the
                // feature was unusable until you happened to type one. Seed a code when switching
                // into a LAN mode; the host reads it out and guests retype it, same as the address.
                val seededCode = s.network.localLinkRoomCode.takeIf { it.length in 4..12 } ?: generateRoomCode()
                apply(
                    when (mode) {
                        // Host is peer 1 by protocol; guests take the derived id so two devices
                        // never share one. Set here (not during composition) so it is a plain edit.
                        1 -> s.copy(network = s.network.copy(dev9EthApi = "Local Link", localLinkHost = true, localLinkPeerId = 1, localLinkRoomCode = seededCode))
                        2 -> s.copy(network = s.network.copy(dev9EthApi = "Local Link", localLinkHost = false, localLinkPeerId = derivedPeerId, localLinkRoomCode = seededCode))
                        else -> s.copy(network = s.network.copy(dev9EthApi = "Sockets", localLinkHost = false))
                    }
                )
            },
        )
        SettingsDivider()

        if (netMode == 0) {
            SegmentedRow(
                label = str("network.ethernetApi"),
                options = apiLabels,
                selectedIndex = apiIndex,
                onChange = { apply(s.copy(network = s.network.copy(dev9EthApi = apiValues[it]))) },
                field = "dev9EthApi",
            )
            SettingsDivider()
            DeviceChooser(
                selected = s.network.dev9EthDevice.ifEmpty { "Auto" },
                adapters = adapters,
                onChange = { apply(s.copy(network = s.network.copy(dev9EthDevice = it.ifEmpty { "Auto" }))) },
            )
            SettingsDivider()
        } else {
            // Ordered to match what players actually do: get the devices on one network, host reads
            // out its address, guests type it in, then everyone matches port and room code.
            HelpText(str("network.localLink.help"))
            // First row in the section, because "which of my games can even do this?" is the first
            // question and the honest answer is a list we should not try to maintain ourselves.
            ActionRow(
                controllerId = "network.localLink.gamesList",
                label = str("network.localLink.gamesList"),
                description = str("network.localLink.gamesList.desc"),
                context = context,
            ) {
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(LAN_GAMES_URL),
                )
            }
            SettingsDivider()
            ActionRow(
                controllerId = "network.localLink.wifi",
                label = str("network.localLink.wifiSettings"),
                description = str("network.localLink.wifiSettings.desc"),
                context = context,
            ) {
                android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            }
            SettingsDivider()
            if (netMode == 1) {
                ReadOnlyRow(
                    label = str("network.localLink.hostAddress"),
                    value = localAddresses.joinToString(", ").ifEmpty { str("network.localLink.noAddress") },
                    description = str("network.localLink.hostAddress.desc"),
                )
            } else {
                LocalLinkRow(
                    controllerId = "network.localLink.address",
                    label = str("network.localLink.address"),
                    value = s.network.localLinkAddress,
                    description = str("network.localLink.address.desc"),
                    fieldLabel = str("network.address"),
                ) { apply(s.copy(network = s.network.copy(localLinkAddress = it))) }
            }
            SettingsDivider()
            LocalLinkRow(
                controllerId = "network.localLink.port",
                label = str("network.localLink.port"),
                value = s.network.localLinkPort.toString(),
                description = str("network.localLink.port.desc"),
                fieldLabel = str("network.localLink.port"),
            ) { apply(s.copy(network = s.network.copy(localLinkPort = it.toIntOrNull()?.coerceIn(1024, 65535) ?: 19072))) }
            SettingsDivider()
            LocalLinkRow(
                controllerId = "network.localLink.roomCode",
                label = str("network.localLink.roomCode"),
                value = s.network.localLinkRoomCode,
                description = str("network.localLink.roomCode.desc"),
                fieldLabel = str("network.localLink.roomCode"),
                onGenerate = { apply(s.copy(network = s.network.copy(localLinkRoomCode = generateRoomCode()))) },
            ) {
                // Uppercased to match the native side, which normalises before deriving the key.
                // An out-of-range code disables DEV9 entirely, so refuse it rather than storing it.
                val cleaned = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12)
                apply(s.copy(network = s.network.copy(localLinkRoomCode = cleaned.ifEmpty { s.network.localLinkRoomCode })))
            }
            // Loud, visible warning instead of the silent "no network adapter" the game reports.
            if (s.network.localLinkRoomCode.length !in 4..12)
                HelpText(str("network.localLink.roomCode.invalid"))
            SettingsDivider()
            ReadOnlyRow(
                label = str("network.localLink.peerId"),
                value = (if (netMode == 1) 1 else s.network.localLinkPeerId).toString(),
                description = str("network.localLink.peerId.desc"),
            )
            HelpText(str("network.localLink.limits"))
            Spacer(Modifier.height(8.dp))
        }

        // Everything below is Online-only: DHCP interception, the PS2's own IP/mask/gateway, DNS
        // modes and host mappings all describe how the emulated adapter talks to the internet.
        // Local Link assigns those itself from the peer id, so showing them in LAN mode would be
        // presenting settings that silently do nothing.
        if (netMode == 0) {
            DhcpSection(s) { apply(it) }
            Spacer(Modifier.height(12.dp))
            InternalDnsSection(s) { apply(it) }
            Spacer(Modifier.height(12.dp))
        } // end Online-only block
        ToggleRow(str("network.logDhcp"), s.network.dev9EthLogDhcp, field = "dev9EthLogDhcp") {
            apply(s.copy(network = s.network.copy(dev9EthLogDhcp = it)))
        }
        SettingsDivider()
        ToggleRow(str("network.logDns"), s.network.dev9EthLogDns, field = "dev9EthLogDns") {
            apply(s.copy(network = s.network.copy(dev9EthLogDns = it)))
        }
        SettingsDivider()
        ToggleRow(str("network.enableDev9VirtualHdd"), s.network.dev9HddEnable, field = "dev9HddEnable") {
            apply(s.copy(network = s.network.copy(dev9HddEnable = it, dev9HddFile = s.network.dev9HddFile.ifEmpty { "DEV9hdd.raw" })))
        }
        SettingsDivider()
        HddFileRow(
            fileName = s.network.dev9HddFile.ifEmpty { "DEV9hdd.raw" },
            onChange = { apply(s.copy(network = s.network.copy(dev9HddFile = it.ifEmpty { "DEV9hdd.raw" }))) },
            onReset = { apply(s.copy(network = s.network.copy(dev9HddFile = "DEV9hdd.raw"))) },
        )
        HelpText(str("network.hddImage.help"))

        Spacer(Modifier.height(16.dp))
        Text(
            str("network.usb.header"),
            color = Colors.pasx2_blue,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ToggleRow(str("network.emulateUsbKeyboard"), s.system.usbKeyboard, field = "usbKeyboard") {
            apply(s.copy(system = s.system.copy(usbKeyboard = it)))
        }
        HelpText(str("network.usbKeyboard.help"))
    }
}

// ---------------------------------------------------------------------------------------------
// Intercept DHCP + Internal DNS, laid out like PCSX2's Qt "Intercept DHCP" / "Internal DNS" tabs.
//
// What actually reaches the core matters more than mirroring the desktop dialog field-for-field:
//  * Sockets (the normal Android backend) ALWAYS runs the internal DHCP server and forces the PS2
//    IP, subnet mask and gateway itself (sockets.cpp: InitInternalServer(..., true, ps2IP, subnet,
//    gateway)). Showing editable IP/mask/gateway rows there presented settings that did nothing, so
//    in Sockets mode only the DNS rows are shown and Intercept DHCP reads as always on.
//  * PCAP/TAP honour InterceptDHCP, AutoMask/AutoGateway and the typed addresses. Typing a gateway
//    or mask switches its Auto toggle off, for the same reason typing a DNS address used to switch
//    that slot to Manual: left on Auto, the typed value was saved and then silently ignored.
//  * A DNS address row is only shown while its slot is Manual, the only mode that reads it.
// ---------------------------------------------------------------------------------------------

/** Mode names exactly as Pcsx2Config's DnsModeNames spells them; they are written to the INI. */
private val DNS_MODES = listOf("Manual", "Auto", "Internal")

/** The address the core hands out as DNS in Internal mode (DHCP_Server.cpp). Shown so people can
 *  recognise it when a game's network screen displays it. */
private const val INTERNAL_DNS_IP = "192.0.2.1"

private fun isSocketsApi(api: String) = api.isEmpty() || api == "Sockets"

@Composable
private fun DhcpSection(s: Settings, save: (Settings) -> Unit) {
    val net = s.network
    val sockets = isSocketsApi(net.dev9EthApi)
    val dhcpActive = sockets || net.dev9InterceptDhcp

    SectionHeader(str("network.dhcp.header"))
    if (sockets) {
        ReadOnlyRow(
            label = str("network.interceptDhcp"),
            value = str("network.dhcp.alwaysOn"),
            description = str("network.dhcp.socketsNote"),
        )
    } else {
        ToggleRow(
            str("network.interceptDhcp"),
            net.dev9InterceptDhcp,
            description = str("network.dhcp.interceptDesc"),
            field = "dev9InterceptDhcp",
        ) { save(s.copy(network = net.copy(dev9InterceptDhcp = it))) }
    }
    SettingsDivider()

    if (!dhcpActive) {
        HelpText(str("network.dhcp.offHelp"))
        return
    }

    if (!sockets) {
        IpFieldRow("network.ps2Ip", str("network.ps2Ip"), net.dev9Ps2Ip) {
            save(s.copy(network = net.copy(dev9Ps2Ip = it)))
        }
        SettingsDivider()
        ToggleRow(str("network.autoSubnetMask"), net.dev9AutoMask, field = "dev9AutoMask") {
            save(s.copy(network = net.copy(dev9AutoMask = it)))
        }
        SettingsDivider()
        IpFieldRow(
            "network.subnetMask", str("network.subnetMask"), net.dev9Mask,
            description = if (net.dev9AutoMask) str("network.dhcp.typingTurnsOffAuto") else null,
        ) { save(s.copy(network = net.copy(dev9Mask = it, dev9AutoMask = if (it != "0.0.0.0") false else net.dev9AutoMask))) }
        SettingsDivider()
        ToggleRow(str("network.autoGateway"), net.dev9AutoGateway, field = "dev9AutoGateway") {
            save(s.copy(network = net.copy(dev9AutoGateway = it)))
        }
        SettingsDivider()
        IpFieldRow(
            "network.gateway", str("network.gateway"), net.dev9Gateway,
            description = if (net.dev9AutoGateway) str("network.dhcp.typingTurnsOffAuto") else null,
        ) { save(s.copy(network = net.copy(dev9Gateway = it, dev9AutoGateway = if (it != "0.0.0.0") false else net.dev9AutoGateway))) }
        SettingsDivider()
    }

    DnsSlot(
        label = str("network.primaryDns"),
        mode = net.dev9ModeDns1,
        address = net.dev9Dns1,
        modeField = "dev9ModeDns1",
        addressId = "network.dns1",
        addressLabel = str("network.dns1"),
        onMode = { save(s.copy(network = net.copy(dev9ModeDns1 = it))) },
        onAddress = { save(s.copy(network = net.copy(dev9Dns1 = it))) },
    )
    SettingsDivider()
    DnsSlot(
        label = str("network.secondaryDns"),
        mode = net.dev9ModeDns2,
        address = net.dev9Dns2,
        modeField = "dev9ModeDns2",
        addressId = "network.dns2",
        addressLabel = str("network.dns2"),
        onMode = { save(s.copy(network = net.copy(dev9ModeDns2 = it))) },
        onAddress = { save(s.copy(network = net.copy(dev9Dns2 = it))) },
    )
    SettingsDivider()
}

/** One DNS slot: the Manual/Auto/Internal picker, plus the address row when (and only when) the
 *  slot is Manual, since that is the only mode the core reads the address in. */
@Composable
private fun DnsSlot(
    label: String,
    mode: String,
    address: String,
    modeField: String,
    addressId: String,
    addressLabel: String,
    onMode: (String) -> Unit,
    onAddress: (String) -> Unit,
) {
    val index = DNS_MODES.indexOf(mode).let { if (it >= 0) it else 1 }
    SegmentedRow(
        label = label,
        options = listOf(str("network.dnsMode.manual"), str("network.dnsMode.auto"), str("network.dnsMode.internal")),
        selectedIndex = index,
        description = when (DNS_MODES[index]) {
            "Manual" -> str("network.dnsMode.manual.desc")
            "Internal" -> str("network.dnsMode.internal.desc").replace("{ip}", INTERNAL_DNS_IP)
            else -> str("network.dnsMode.auto.desc")
        },
        field = modeField,
        onChange = { onMode(DNS_MODES[it]) },
    )
    if (DNS_MODES[index] == "Manual") {
        IpFieldRow(addressId, "   ↳ $addressLabel", address, onChange = onAddress)
    }
}

@Composable
private fun InternalDnsSection(s: Settings, save: (Settings) -> Unit) {
    val net = s.network
    val hosts = net.dev9EthHosts
    val dhcpActive = isSocketsApi(net.dev9EthApi) || net.dev9InterceptDhcp
    val internalInUse = dhcpActive && (net.dev9ModeDns1 == "Internal" || net.dev9ModeDns2 == "Internal")

    SectionHeader(str("network.internalDns.header"))
    HelpText(str("network.internalDns.help"))

    // The entries are inert unless a DNS slot points at the internal resolver. That was the easiest
    // mistake to make (the list and the DNS mode live in different places), so say so right here
    // and offer the fix, rather than letting a fan server "not work" with no hint why.
    if (hosts.any { it.enabled } && !internalInUse) {
        HelpText(str("network.internalDns.inactiveWarning"), Modifier.padding(top = 0.dp))
        TapRow(
            controllerId = "network.internalDns.useInternal",
            label = str("network.internalDns.useInternal"),
            description = str("network.internalDns.useInternal.desc"),
            accent = true,
        ) {
            save(
                s.copy(
                    network = net.copy(
                        dev9ModeDns1 = "Internal",
                        dev9ModeDns2 = "Internal",
                        // Internal only exists behind the DHCP server; PCAP/TAP need it on.
                        dev9InterceptDhcp = if (isSocketsApi(net.dev9EthApi)) net.dev9InterceptDhcp else true,
                    ),
                ),
            )
        }
        SettingsDivider()
    }

    hosts.forEachIndexed { i, entry ->
        HostEntryCard(
            index = i,
            entry = entry,
            onUpdate = { updated ->
                // Built from the list as it was when the editor opened: the keyboard reports every
                // keystroke, and each one replaces this entry rather than appending.
                save(s.copy(network = net.copy(dev9EthHosts = hosts.toMutableList().also { it[i] = updated })))
            },
            onDelete = {
                save(s.copy(network = net.copy(dev9EthHosts = hosts.toMutableList().also { it.removeAt(i) })))
            },
        )
        Spacer(Modifier.height(8.dp))
    }

    // Adding starts with the hostname, because an entry without one is meaningless (and is dropped
    // on load). Name defaults to a label like PCSX2's "New Host" and the IP is set on the card.
    val hostPrompt = str("network.internalDns.hostname.hint")
    val newName = str("network.internalDns.defaultName")
    // A tap opens Android's keyboard; a gamepad confirm opens the D-pad key grid.
    val addWith = { system: Boolean ->
        com.armsx2.ui.home.LibraryKeyboard.open(
            "",
            { typed ->
                val url = cleanHostname(typed)
                val list = if (url.isEmpty()) hosts
                    else hosts + Dev9HostMapping(name = "$newName ${hosts.size + 1}", url = url, ip = "0.0.0.0", enabled = true)
                save(s.copy(network = net.copy(dev9EthHosts = list)))
            },
            hostPrompt,
            systemIme = system,
            type = androidx.compose.ui.text.input.KeyboardType.Uri,
        )
    }
    TapRow(
        controllerId = "network.internalDns.add",
        label = "+ " + str("network.internalDns.add"),
        description = str("network.internalDns.add.desc"),
        accent = true,
        onTap = { addWith(true) },
        onPadConfirm = { addWith(false) },
    )
}

/** One Internal DNS entry as a card: Name, Hostname, IP address, Enabled, Delete — the same
 *  columns as PCSX2's table, stacked because a table does not fit a phone in portrait. */
@Composable
private fun HostEntryCard(
    index: Int,
    entry: Dev9HostMapping,
    onUpdate: (Dev9HostMapping) -> Unit,
    onDelete: () -> Unit,
) {
    val id = "network.internalDns.host$index"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Text(
            entry.name.ifBlank { "${str("network.host")} ${index + 1}" },
            color = if (entry.enabled) Colors.pasx2_blue else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
        TextFieldRow(
            controllerId = "$id.name",
            label = str("network.internalDns.name"),
            value = entry.name,
            emptyText = str("network.localLink.notSet"),
            fieldLabel = str("network.internalDns.name"),
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
        ) { onUpdate(entry.copy(name = it.trim())) }
        TextFieldRow(
            controllerId = "$id.url",
            label = str("network.internalDns.hostname"),
            value = entry.url,
            emptyText = str("network.localLink.notSet"),
            fieldLabel = str("network.internalDns.hostname.hint"),
        ) {
            // An emptied hostname keeps the old one; Delete is the way to remove an entry.
            val url = cleanHostname(it)
            if (url.isNotEmpty()) onUpdate(entry.copy(url = url))
        }
        IpFieldRow("$id.ip", str("network.internalDns.ip"), entry.ip) { onUpdate(entry.copy(ip = it)) }
        // Not the shared ToggleRow: that registers for pad navigation as "toggle:<label>", so every
        // card's "Enabled" would share one id and only one of them would be reachable.
        val flip = {
            com.armsx2.MenuSfx.play(if (!entry.enabled) com.armsx2.MenuSfx.Event.TOGGLE_ON else com.armsx2.MenuSfx.Event.TOGGLE_OFF)
            onUpdate(entry.copy(enabled = !entry.enabled))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = flip)
                .controllerFocusable("$id.enabled", onConfirm = flip)
                .padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(str("network.internalDns.enabled"), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = entry.enabled, onCheckedChange = { flip() })
        }
        TapRow(
            controllerId = "$id.delete",
            label = str("network.internalDns.delete"),
            description = null,
            danger = true,
            onTap = onDelete,
        )
    }
}

/** Normalises what people paste or type into a hostname: the core lowercases the game's query but
 *  compares it against the stored entry as-is (DNS_Server::CheckHostList), so a capital letter —
 *  which phone keyboards add on their own — meant the entry could never match. Also drops a
 *  scheme, path, port and stray whitespace, since a guide's "http://patch.example.com/" is a common
 *  thing to copy. */
internal fun cleanHostname(raw: String): String {
    var h = raw.trim().lowercase()
    h = h.substringAfter("://")
    h = h.substringBefore('/').substringBefore('?').substringBefore('#')
    h = h.substringBefore(':') // port
    return h.filter { !it.isWhitespace() }.trimEnd('.')
}

/** Returns [raw] as a canonical dotted IPv4 ("054.1.2.3" → "54.1.2.3"), or null while it is not one
 *  yet. The keyboard reports every keystroke, so a half-typed address must not be stored. */
internal fun parseIpv4(raw: String): String? {
    val parts = raw.trim().split('.')
    if (parts.size != 4) return null
    val nums = parts.map { p ->
        if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
        p.toInt().takeIf { it in 0..255 } ?: return null
    }
    return nums.joinToString(".")
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = Colors.pasx2_blue,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** An IPv4 field. Saves only once the text is a complete address; until then the keyboard simply
 *  keeps what is being typed and the stored value is left alone. */
@Composable
private fun IpFieldRow(
    controllerId: String,
    label: String,
    value: String,
    description: String? = null,
    onChange: (String) -> Unit,
) {
    TextFieldRow(
        controllerId = controllerId,
        label = label,
        value = value.ifEmpty { "0.0.0.0" },
        emptyText = "0.0.0.0",
        fieldLabel = str("network.ipHint"),
        description = description,
    ) { typed -> parseIpv4(typed)?.let(onChange) }
}

/** Label + value row that edits through LibraryKeyboard (pad-navigable, unlike a dialog) and
 *  registers with the controller registry so it is reachable on a gamepad. */
@Composable
private fun TextFieldRow(
    controllerId: String,
    label: String,
    value: String,
    emptyText: String,
    fieldLabel: String,
    description: String? = null,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
    onChange: (String) -> Unit,
) {
    // Touch opens Android's own keyboard; a gamepad confirm opens the D-pad key grid, since the
    // system keyboard cannot be driven with a controller.
    val tap = { com.armsx2.ui.home.LibraryKeyboard.open(value, onChange, fieldLabel, systemIme = true, type = keyboardType) }
    val padEdit = { com.armsx2.ui.home.LibraryKeyboard.open(value, onChange, fieldLabel, type = keyboardType) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = tap)
            .controllerFocusable(controllerId, onConfirm = padEdit)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                value.ifEmpty { emptyText },
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (description != null) {
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** A tappable action row (Add host, Delete, the "use Internal DNS" fix). */
@Composable
private fun TapRow(
    controllerId: String,
    label: String,
    description: String?,
    accent: Boolean = false,
    danger: Boolean = false,
    /** Gamepad confirm, when it should differ from a tap (e.g. key grid vs system keyboard). */
    onPadConfirm: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = onTap)
            .controllerFocusable(controllerId, onConfirm = onPadConfirm ?: onTap)
            .padding(horizontal = 6.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = when {
                danger -> Color(0xFFFF6B6B)
                accent -> Colors.pasx2_blue
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (description != null) {
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** This device's own LAN IPv4 addresses, so a host can read one out to the guests instead of being
 *  told to go hunting in Android's settings. Hotspot interfaces are included on purpose — a hotspot
 *  is the most reliable way to get two handhelds onto one network. */
private fun enumerateLocalIPv4(): List<String> {
    val out = linkedSetOf<String>()
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        for (iface in interfaces.toList()) {
            val usable = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress)
                    addr.hostAddress?.let { out.add(it) }
            }
        }
    }
    return out.toList()
}

/** A fresh 8-character room code. Seeded automatically when LAN mode is first selected, because an
 *  empty code silently disables DEV9 (see the Network mode onChange for the full failure chain).
 *  Uppercase alphanumerics only, matching what the native side normalises to. */
private fun generateRoomCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I/O/0/1 — these get read aloud
    return (1..8).map { alphabet[kotlin.random.Random.nextInt(alphabet.length)] }.joinToString("")
}

/** A stable guest peer id in 2..65533, derived from ANDROID_ID so it differs per device and never
 *  needs to be chosen by hand. 1 is reserved for the host by the wire protocol. */
private fun derivePeerId(context: android.content.Context): Int {
    val seed = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
    }.getOrNull().orEmpty().ifEmpty { android.os.Build.FINGERPRINT }
    // 65532 slots starting at 2; abs() on the hash, guarding Int.MIN_VALUE.
    val h = seed.hashCode()
    val positive = if (h == Int.MIN_VALUE) 0 else if (h < 0) -h else h
    return 2 + (positive % 65532)
}

/** A tappable label+subtitle row that fires an Intent. Used for the Wi-Fi shortcut (the devices have
 *  to be on one network before any of this works, and that is the step people miss) and for the
 *  supported-games list. Registers with the pad-nav registry — without that the whole Local Link
 *  section was unreachable on a controller, since only the shared ToggleRow/SegmentedRow widgets
 *  self-register and every custom row here was skipped. */
@Composable
private fun ActionRow(
    controllerId: String,
    label: String,
    description: String,
    context: android.content.Context,
    intent: () -> android.content.Intent,
) {
    val fire = {
        // runCatching: no ACTION_VIEW handler (no browser) or a blocked settings intent must not
        // take the settings screen down with it.
        runCatching {
            context.startActivity(intent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Unit
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = fire)
            .controllerFocusable(controllerId, onConfirm = fire)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

/** Wikipedia's LAN-games section: the authoritative answer to "which games can I actually use this
 *  with?", which is the first thing anyone asks. Kept as a link rather than a baked-in list so it
 *  can't go stale in our strings. */
private const val LAN_GAMES_URL =
    "https://en.wikipedia.org/wiki/List_of_PlayStation_2_online_games#LAN_Games"

/** A non-editable value row (host address, local peer id) — same shape as the editable rows so the
 *  section reads consistently, but with no tap target, because these are computed, not chosen. */
@Composable
private fun ReadOnlyRow(label: String, value: String, description: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value,
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * A Local Link field: like [TextFieldRow], but it always shows a description under the label and
 * can carry a Generate action. The address rows use [IpFieldRow], which only saves a complete IPv4
 * address — right for DNS and gateways, wrong for a room code, a port or a peer id.
 *
 * onChange receives the trimmed text; callers do their own validation/coercion.
 */
@Composable
private fun LocalLinkRow(
    controllerId: String,
    label: String,
    value: String,
    description: String,
    fieldLabel: String,
    /** When supplied, adds a Generate action (button + D-pad Right) that fills the field. Used for
     *  the room code, which has validity rules a person shouldn't have to remember. */
    onGenerate: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    // Text entry goes through LibraryKeyboard, NOT an AlertDialog. A Compose dialog takes its own
    // focused window and swallows gamepad keys, so a pad user could open it and then be stuck with
    // no way to type or dismiss. LibraryKeyboard is D-pad navigable by design, and it also honours
    // the "Use system keyboard" preference for touch users.
    val edit = {
        com.armsx2.ui.home.LibraryKeyboard.open(value, onChange, fieldLabel, type = androidx.compose.ui.text.input.KeyboardType.Uri)
    }
    val tap = {
        com.armsx2.ui.home.LibraryKeyboard.open(value, onChange, fieldLabel, systemIme = true, type = androidx.compose.ui.text.input.KeyboardType.Uri)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = tap)
            .controllerFocusable(
                controllerId,
                onConfirm = edit,
                // D-pad Right regenerates, matching how other rows use left/right to adjust.
                onRight = onGenerate,
            )
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value.ifEmpty { str("network.localLink.notSet") },
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onGenerate != null) {
                // One-tap valid code, without opening the editor. Its own clickable consumes the
                // tap, so it does not also open the row's edit dialog.
                TextButton(onClick = onGenerate) { Text(str("network.localLink.generate")) }
            }
        }
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun DeviceChooser(
    selected: String,
    adapters: List<String>,
    onChange: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(str("network.ethernetDevice"), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        adapters.forEach { adapter ->
            val active = adapter == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable { onChange(adapter) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    adapter,
                    color = if (active) Colors.pasx2_blue else Color(0xFFCCCCCC),
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (active) {
                    Text(str("network.selected"), color = Colors.pasx2_blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HddFileRow(fileName: String, onChange: (String) -> Unit, onReset: () -> Unit) {
    // Same treatment as TextFieldRow: the keyboard, not a dialog. D-pad Left resets, matching
    // how every other row in this app uses left/right on the focused control.
    val fieldLabel = str("network.hddImage.fieldLabel")
    val edit = {
        com.armsx2.ui.home.LibraryKeyboard.open(fileName, { onChange(it.trim()) }, fieldLabel, type = androidx.compose.ui.text.input.KeyboardType.Uri)
    }
    val tap = {
        com.armsx2.ui.home.LibraryKeyboard.open(fileName, { onChange(it.trim()) }, fieldLabel, systemIme = true, type = androidx.compose.ui.text.input.KeyboardType.Uri)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowAura())
            .clickable(onClick = tap)
            .controllerFocusable("network.hddImage", onConfirm = edit, onLeft = onReset)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(str("network.hddImage.title"), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(fileName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                str("action.reset"),
                color = Colors.pasx2_blue,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onReset() }.padding(start = 8.dp),
            )
        }
    }
}

private fun enumerateAdapters(): List<String> {
    val out = linkedSetOf("Auto")
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        interfaces.toList()
            .filter { iface ->
                runCatching {
                    iface.isUp && !iface.isLoopback && !iface.isVirtual
                }.getOrDefault(false)
            }
            .mapTo(out) { it.name }
    }
    return out.toList()
}
