package dev.mcatismeow.fishskin.runtime.client;

import dev.mcatismeow.fishskin.runtime.SkinConfig;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.audio.capture.ClientActivation;
import su.plo.voice.api.client.event.audio.capture.AudioCaptureProcessedEvent;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.util.AudioUtil;

/**
 * Plasmo Voice addon: reports whether the local player is talking. This class links against Plasmo Voice, so it is
 * only ever touched through {@link PlasmoBridge}, and only when Plasmo Voice is installed.
 * <p>
 * "Talking" means the mic is sending (voice activation or push-to-talk) and the voice is actually loud enough,
 * so holding push-to-talk in silence keeps the mouth closed.
 */
@Addon(id = "pv-addon-fishskin", name = "FishSkin talking skins", version = "1.0.0", authors = {"fishskin"})
public final class PlasmoTalk implements AddonInitializer {
    @InjectPlasmoVoice
    private PlasmoVoiceClient voice;
    private volatile long lastVoice; // System.currentTimeMillis() of the last loud enough mic frame

    @Override
    public void onAddonInitialize() {
        PlasmoVoiceClient v = voice;
        if (v != null) v.getEventBus().register(this, this);
    }

    @Override
    public void onAddonShutdown() {
        PlasmoVoiceClient v = voice;
        if (v != null) {
            try { v.getEventBus().unregister(this); } catch (Throwable ignored) {}
        }
        voice = null;
    }

    /** Runs on Plasmo Voice's capture thread for every mic frame. */
    @EventSubscribe
    public void onCapture(AudioCaptureProcessedEvent event) {
        try {
            if (AudioUtil.calculateHighestAudioLevel(event.getProcessed().getMono()) >= SkinConfig.plasmoMinLevelDb()) {
                lastVoice = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
            // a frame we cannot read: leave the mouth as it is
        }
    }

    boolean isTalking() {
        PlasmoVoiceClient v = voice;
        if (v == null || System.currentTimeMillis() - lastVoice > SkinConfig.plasmoHoldMs()) return false;
        try {
            for (ClientActivation a : v.getActivationManager().getActivations()) {
                if (a.isActive()) return true;
            }
        } catch (Throwable ignored) {
            // the mic or connection is not ready yet
        }
        return false;
    }
}
