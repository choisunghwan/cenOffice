package com.cenoffice.meeting;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeetingMediaTokenTest {
    @Test void assistantGrantUsesLiveKitStringSourceNamesAndCannotSubscribe() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{x.sendResponseHeaders(200,-1);x.close();});server.start();
        try{
            var media=new MeetingMedia("test-key","test-secret-test-secret-test-secret", "ws://localhost:7880",
                "http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());
            var claims=SignedJWT.parse(media.assistantGrant("meeting","turn").token()).getJWTClaimsSet();
            var video=claims.getJSONObjectClaim("video");
            assertEquals(List.of("microphone"),video.get("canPublishSources"));
            assertEquals(false,video.get("canSubscribe"));
            assertEquals(false,video.get("canPublishData"));
            assertEquals("cen-meeting",video.get("room"));
        }finally{server.stop(0);}
    }

    @org.junit.jupiter.api.Test void apiUrlGivenAsWebSocketIsUsedAsHttps() {
        org.junit.jupiter.api.Assertions.assertEquals("https://cenhub-x.livekit.cloud", MeetingMedia.httpUrl("wss://cenhub-x.livekit.cloud/"));
        org.junit.jupiter.api.Assertions.assertEquals("http://127.0.0.1:7880", MeetingMedia.httpUrl("ws://127.0.0.1:7880"));
        org.junit.jupiter.api.Assertions.assertEquals("https://a.livekit.cloud", MeetingMedia.httpUrl("https://a.livekit.cloud"));
    }

    /** 상태 확인은 실제 키로 LiveKit 에 요청해 본다: 키가 거부되면 key_rejected, 응답하면 ok, 접속이 안 되면 unreachable */
    @org.junit.jupiter.api.Test void statusCheckUsesTheKeyAgainstLiveKit() throws Exception {
        var code = new java.util.concurrent.atomic.AtomicInteger(401);
        var auth = new java.util.concurrent.atomic.AtomicReference<String>();
        var srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/twirp/livekit.RoomService/ListRooms", ex -> { auth.set(ex.getRequestHeaders().getFirst("Authorization")); ex.sendResponseHeaders(code.get(), -1); ex.close(); });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            String secret = "s".repeat(40);
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            org.junit.jupiter.api.Assertions.assertEquals("key_rejected", new MeetingMedia("APIkey", secret, "ws://x", base, om).check());
            org.junit.jupiter.api.Assertions.assertTrue(auth.get().startsWith("Bearer "));
            code.set(200);
            var ok = new MeetingMedia("APIkey", secret, "ws://x", base.replace("http://", "ws://"), om); // wss/ws 로 넣어도 된다
            org.junit.jupiter.api.Assertions.assertEquals("ok", ok.check());
            org.junit.jupiter.api.Assertions.assertTrue(ok.available());
            org.junit.jupiter.api.Assertions.assertEquals("not_configured", new MeetingMedia("", "", "ws://x", base, om).check());
        } finally { srv.stop(0); }
        org.junit.jupiter.api.Assertions.assertEquals("unreachable", new MeetingMedia("APIkey", "s".repeat(40), "ws://x", "http://127.0.0.1:1", new com.fasterxml.jackson.databind.ObjectMapper()).check());
    }

    @org.junit.jupiter.api.Test void pastedCredentialsAreCleaned() {
        org.junit.jupiter.api.Assertions.assertEquals("abcDEF123", MeetingMedia.credential("  LIVEKIT_API_SECRET=abc\nDEF 123 "));
        org.junit.jupiter.api.Assertions.assertEquals("APIxyz", MeetingMedia.credential("\"APIxyz\""));
        org.junit.jupiter.api.Assertions.assertEquals("APIxyz", MeetingMedia.credential("LIVEKIT_API_KEY=APIxyz"));
    }
}
