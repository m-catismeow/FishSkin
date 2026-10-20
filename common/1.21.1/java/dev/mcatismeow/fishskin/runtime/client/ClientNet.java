package dev.mcatismeow.fishskin.runtime.client;

import dev.mcatismeow.fishskin.runtime.block.SkinBlocks;
import dev.mcatismeow.fishskin.runtime.SkinConfig;
import dev.mcatismeow.fishskin.runtime.net.Messages;

/** Entry points for packets that arrive on the client. Kept apart so dedicated servers never load client classes. */
public final class ClientNet {
    private ClientNet() {}

    public static void onAnnounce(Messages.Announce m) { ClientSkins.onAnnounce(m); }

    public static void onDownload(Messages.Download m) { ClientSkins.onDownload(m); }

    public static void onSpeakState(Messages.SpeakState m) { ClientSkins.onSpeakState(m); }

    /** The server decides who may open the wardrobe where; its settings replace ours until we leave. */
    public static void onSettings(Messages.Settings m) {
        SkinConfig.applyRemote(m.json());
        SkinBlocks.reloadMatches();
        ClientInit.settingsChanged();
    }
}
