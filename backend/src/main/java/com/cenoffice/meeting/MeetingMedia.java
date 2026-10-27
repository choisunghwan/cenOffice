package com.cenoffice.meeting;

import com.cenoffice.api.ApiException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** LiveKit is a separate media server. Its secret never goes to the browser. */
@Component
@PropertySource(value = "file:./.meeting-local.properties", ignoreResourceNotFound = true)
public class MeetingMedia {
    private final String key, secret, url, apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    public MeetingMedia(@Value("${livekit.api-key:${LIVEKIT_API_KEY:}}") String key,
                        @Value("${livekit.api-secret:${LIVEKIT_API_SECRET:}}") String secret,
                        @Value("${livekit.url:${LIVEKIT_URL:ws://localhost:7880}}") String url,
                        @Value("${livekit.api-url:${LIVEKIT_API_URL:http://127.0.0.1:7880}}") String apiUrl,
                        ObjectMapper json) {
        this.key=key; this.secret=secret; this.url=url; this.apiUrl=apiUrl; this.json=json;
    }
    public boolean configured() { return !key.isBlank() && secret.length() >= 32; }
    public record Grant(String url, String token) {}
    public Grant grant(String id, String username, String displayName) {
        if (!available()) throw unavailable();
        return new Grant(url, token(username, displayName, Map.of("room", room(id), "roomJoin", true,
            "canPublish", true, "canSubscribe", true, "canPublishData", false)));
    }
    public boolean available() {
        if (!configured()) return false;
        try { return http.send(HttpRequest.newBuilder(URI.create(apiUrl + "/")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }
    public void end(String id) {
        if (!configured()) return; // A session may have been created before media setup.
        try {
            var req = HttpRequest.newBuilder(URI.create(apiUrl + "/twirp/livekit.RoomService/DeleteRoom"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token("meeting-service", "", Map.of("roomCreate", true)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("room",room(id))))).build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200 && status != 404) throw unavailable();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception e) { throw unavailable(); }
    }
    private String token(String identity, String name, Map<String,Object> video) {
        try {
            Instant now=Instant.now();
            var claims=new JWTClaimsSet.Builder().issuer(key).subject(identity).issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(30)))
                .claim("name",name).claim("video",video).build();
            var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims);
            jwt.sign(new MACSigner(secret)); return jwt.serialize();
        } catch (Exception e) { throw unavailable(); }
    }
    static String room(String id) { return "cen-"+id; }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "화상회의 서버에 연결할 수 없습니다. scripts/start-meeting-demo.ps1로 실행해 주세요."); }
}
