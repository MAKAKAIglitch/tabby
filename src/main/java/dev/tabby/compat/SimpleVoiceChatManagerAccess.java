package dev.tabby.compat;

/**
 * Optional Mixin bridge to Simple Voice Chat's single client-global manager.
 * The interface is only implemented when the "voicechat" mod is installed.
 */
public interface SimpleVoiceChatManagerAccess {
    void tabby$invokeOnJoinWorld();

    void tabby$invokeOnDisconnect();
}
