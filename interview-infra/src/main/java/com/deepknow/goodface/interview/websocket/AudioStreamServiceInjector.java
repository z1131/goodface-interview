package com.deepknow.goodface.interview.websocket;

import com.deepknow.goodface.interview.domain.session.service.AudioStreamService;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class AudioStreamServiceInjector {

    private final AudioStreamService audioStreamService;

    public AudioStreamServiceInjector(AudioStreamService audioStreamService) {
        this.audioStreamService = audioStreamService;
    }

    @PostConstruct
    public void inject() {
        AudioStreamWebSocketHandler.setAudioStreamService(audioStreamService);
    }
}