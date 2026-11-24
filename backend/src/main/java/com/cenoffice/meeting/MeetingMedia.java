package com.cenoffice.meeting;

import com.cenoffice.common.api.ApiException;
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
        // 대시보드에 붙여 넣을 때 들어간 공백을 지우고, API 주소를 wss:// 로 넣었어도 https:// 로 바꿔 쓴다
        this.key=key.strip(); this.secret=secret.strip(); this.url=url.strip(); this.apiUrl=httpUrl(apiUrl.strip()); this.json=json;
    }
    static String httpUrl(String u) {
        String r = u.replaceFirst("^(?i)wss://", "https://").replaceFirst("^(?i)ws://", "http://");
        return r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
    }
    public boolean configured() { return !key.isBlank() && secret.length() >= 32; }
    public record Grant(String url, String token) {}
    public Grant grant(String id, String username, String displayName) {
        if (!available()) throw unavailable();
        return new Grant(url, token(username, displayName, Map.of("room", room(id), "roomJoin", true,
            "canPublish", true, "canSubscribe", true, "canPublishData", false)));
    }
    /** ok · not_configured(값 없음) · unreachable(주소에 접속 안 됨) · key_rejected(키·시크릿이 LiveKit 에서 거부됨) */
    public String check() {
        if (!configured()) return "not_configured";
        var c = cached;
        if (c != null && c.at().isAfter(Instant.now().minusSeconds(15))) return c.result();
        String r;
        try {
            // 실제 키로 서명한 토큰으로 회의방 목록을 요청해 본다. 주소만 맞고 키가 틀려도 "준비 완료"로 보이지 않게
            var req = HttpRequest.newBuilder(URI.create(apiUrl + "/twirp/livekit.RoomService/ListRooms")).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token("meeting-service", "", Map.of("roomList", true)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            int st = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            r = st == 200 ? "ok" : (st == 401 || st == 403) ? "key_rejected" : "unreachable";
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); r = "unreachable"; }
        catch (Exception e) { r = "unreachable"; }
        cached = new Checked(Instant.now(), r);
        return r;
    }
    private record Checked(Instant at, String result) {}
    private volatile Checked cached;
    public boolean available() { return "ok".equals(check()); }
    public Grant assistantGrant(String id,String turnId) {
        if(!available())throw unavailable();
        return new Grant(url,token("cen-ai-"+turnId,"센 (AI)",Map.of("room",room(id),"roomJoin",true,
            "canPublish",true,"canSubscribe",false,"canPublishData",false,"canPublishSources",List.of("microphone"))));
    }
    public void removeAssistant(String id,String turnId) {
        if(!configured())return;
        try {
            var req=HttpRequest.newBuilder(URI.create(apiUrl+"/twirp/livekit.RoomService/RemoveParticipant"))
                .timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+token("meeting-service","",Map.of("room",room(id),"roomAdmin",true)))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("room",room(id),"identity","cen-ai-"+turnId)))).build();
            http.send(req,HttpResponse.BodyHandlers.discarding());
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception ignored){/* Sender also observes STOPPED and disconnects; meeting end deletes the room. */}
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
