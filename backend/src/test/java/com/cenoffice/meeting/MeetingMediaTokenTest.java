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
}
