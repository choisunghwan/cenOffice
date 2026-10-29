package com.cenoffice.meeting;

/** Replace this adapter when an internal TTS endpoint is available. Audio is WAV, not a recording of participants. */
public interface SpeechSynthesizer {
    boolean available();
    byte[] synthesize(String text) throws Exception;
}
