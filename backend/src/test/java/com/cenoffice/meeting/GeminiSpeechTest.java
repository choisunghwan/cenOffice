package com.cenoffice.meeting;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GeminiSpeechTest {
    static byte[] wave() {
        byte[] pcm = new byte[24000 * 2];
        for (int i=0;i<24000;i++) { short v=(short)(Math.sin(i*2*Math.PI*440/24000)*3000); pcm[i*2]=(byte)v; pcm[i*2+1]=(byte)(v>>8); }
        var b=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+pcm.length).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);return b.array();
    }
    @Test void sendsTextAndHeaderSecretAndDecodesWavFromInteractions() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var request=new AtomicReference<String>(); var header=new AtomicReference<String>();
        byte[] wav=wave();
        server.createContext("/tts", x->{
            request.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            header.set(x.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] response=("{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"mime_type\":\"audio/wav\",\"data\":\""+Base64.getEncoder().encodeToString(wav)+"\"}]}]}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,response.length);x.getResponseBody().write(response);x.close();
        });server.start();
        try {
            var tts=new GeminiSpeechSynthesizer(new ObjectMapper(),"test-key","gemini-3.8-flash-lite-tts","Kore",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/tts"));
            assertArrayEquals(wav,tts.synthesize("센의 의견입니다."));
            assertEquals("test-key",header.get());
            var payload=new ObjectMapper().readTree(request.get());
            assertEquals("센의 의견입니다.",payload.path("input").get(0).path("content").get(0).path("text").asText());
            assertEquals("Kore",payload.path("generation_config").path("speech_config").get(0).path("voice").asText());
            assertFalse(request.get().contains("test-key"));
        } finally { server.stop(0); }
    }
    @Test void invalidAudioAndProviderFailureAreSafeErrors() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/bad",x->{byte[] data="{\"error\":\"private-provider-details\"}".getBytes();x.sendResponseHeaders(429,data.length);x.getResponseBody().write(data);x.close();});server.start();
        try {
            var tts=new GeminiSpeechSynthesizer(new ObjectMapper(),"test-key","model","Kore",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/bad"));
            Exception e=assertThrows(Exception.class,()->tts.synthesize("안녕하세요"));
            assertFalse(e.getMessage().contains("private-provider-details"));
            assertFalse(e.getMessage().contains("test-key"));
        }finally{server.stop(0);}
    }
}
