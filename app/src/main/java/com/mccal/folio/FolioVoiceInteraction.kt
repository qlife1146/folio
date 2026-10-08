package com.mccal.folio

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Folio as a full "Digital assistant app". Some phones only offer a real voice interaction service on the side key
 * (One UI's press-and-hold starts the assistant through it, not through ACTION_ASSIST), so Folio provides a minimal
 * one: holding the key shows Folio's assistant picker. It never listens or records.
 */
class FolioVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Folio only shows its picker, so it asks Android not to collect the current app's screen content or a
        // screenshot when the assistant opens.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

class FolioVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = FolioVoiceSession(this)
}

private class FolioVoiceSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // The chosen assistant's voice screen, or Folio's picker (also the fallback if that app was uninstalled).
        val target = SideKeyHold.current(context).intent(context)
            ?: Intent(context, AssistPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AppSecurity.run(context, target.`package` ?: target.component?.packageName ?: context.packageName,
            android.os.Process.myUserHandle()) {
            runCatching { startAssistantActivity(target) }
                .onFailure { runCatching { context.startActivity(target) } }
            hide()
        }
    }
}

/** Android requires an assistant to name a speech recognizer; Folio doesn't do speech, so this one always declines. */
class FolioRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}

/**
 * What holding the side key does: Folio's picker, or talking straight to an assistant that accepts a voice request
 * (Google/Gemini, Claude, Perplexity). Only assistants installed on this phone are offered. Stored on its own so the
 * assistant session can read it without loading Folio's whole state.
 */
internal enum class SideKeyHold(val label: String, val action: String?, val packageName: String?) {
    PICKER("Folio Picker", null, null),
    GOOGLE("Talk to Google", "android.intent.action.VOICE_ASSIST", "com.google.android.googlequicksearchbox"),
    /** Type a search that opens Google's Web results (no AI Overview). Always available. */
    SEARCH_NO_AI("Search Google Without AI", null, null),
    CLAUDE("Talk to Claude", "android.intent.action.VOICE_ASSIST", "com.anthropic.claude"),
    PERPLEXITY("Talk to Perplexity", Intent.ACTION_VOICE_COMMAND, "ai.perplexity.app.android");

    fun intent(context: android.content.Context): Intent? {
        if (this == SEARCH_NO_AI) return Intent(context, AssistPickerActivity::class.java)
            .putExtra(AssistPickerActivity.EXTRA_SEARCH_ONLY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action == null || packageName == null) return null
        AppSecurity.initialize(context)
        if (AppSecurity.isHidden(packageName, android.os.Process.myUserHandle())) return null
        val intent = Intent(action).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    companion object {
        private const val PREFS = "side_key"
        private const val KEY = "hold"
        fun available(context: android.content.Context) = entries.filter { it == PICKER || it.intent(context) != null }
        fun current(context: android.content.Context): SideKeyHold =
            runCatching { valueOf(context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: PICKER.name) }.getOrDefault(PICKER)
        fun set(context: android.content.Context, value: SideKeyHold) =
            context.getSharedPreferences(PREFS, 0).edit().putString(KEY, value.name).apply()
    }
}
