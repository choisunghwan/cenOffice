package com.cenoffice.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiSpeechSynthesizer implements SpeechSynthesizer {
    private final ObjectMapper json;
    private final String key, model, voice;
    private final URI endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GeminiSpeechSynthesizer(ObjectMapper json,
        @Value("${meeting.ai.tts-key:${GEMINI_API_KEY:}}") String key,
        @Value("${meeting.ai.tts-model:${MEETING_TTS_MODEL:gemini-3.8-flash-lite-tts}}") String model,
        @Value("${meeting.ai.tts-voice:${MEETING_TTS_VOICE:Kore}}") String voice,
        @Value("${meeting.ai.tts-endpoint:https://generativelanguage.googleapis.com/v1beta/interactions}") URI endpoint) {
        this.json=json;this.key=key;this.model=model;this.voice=voice;this.endpoint=endpoint;
    }
    public boolean available(){return !key.isBlank();}
    public byte[] synthesize(String text) throws Exception {
        if(!available()) throw new IllegalStateException("TTS API 키가 설정되지 않았습니다");
        var body=Map.of("model",model,
            "input",List.of(Map.of("type","user_input","content",List.of(Map.of("type","text","text",text)))),
            "response_format",Map.of("type","audio","mime_type","audio/wav"),
            "generation_config",Map.of("speech_config",List.of(Map.of("voice",voice))));
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                .header("x-goog-api-key",key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException("음성 생성 실패 (HTTP "+response.statusCode()+")");
            if(response.body().length()>12_000_000) throw new IllegalStateException("음성 응답이 너무 큽니다");
            JsonNode selected=null;
            for(var step:json.readTree(response.body()).path("steps")) {
                if(!"model_output".equals(step.path("type").asText()))continue;
                for(var part:step.path("content"))if("audio".equals(part.path("type").asText()))selected=part;
            }
            if(selected==null)throw new IllegalStateException("음성 응답이 비어 있습니다");
            byte[] wav=Base64.getDecoder().decode(selected.path("data").asText());
            if(wav.length<44 || wav.length>8_000_000 || wav[0]!='R'||wav[1]!='I'||wav[2]!='F'||wav[3]!='F'
                ||wav[8]!='W'||wav[9]!='A'||wav[10]!='V'||wav[11]!='E')
                throw new IllegalStateException("지원하지 않는 음성 형식입니다");
            return wav;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("음성 생성이 중단되었습니다");}
        catch(IllegalStateException e){throw e;}
        catch(Exception e){throw new IllegalStateException("음성 서비스에 연결하지 못했습니다");}
    }
}
