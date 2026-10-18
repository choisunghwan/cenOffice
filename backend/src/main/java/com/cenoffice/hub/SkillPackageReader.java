package com.cenoffice.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 업로드된 SKILL.md / ZIP 을 "신뢰할 수 없는 입력"으로 검증하고 구조화한다.
 *  - 아무것도 디스크에 풀지 않는다(메모리에서만 읽음 → 경로 탐색 무의미). 그래도 위험한 경로/심볼릭 링크/암호화 ZIP 은 거부한다.
 *  - 실행 가능한 파일(확장자 또는 시그니처)과 인증정보 패턴이 있으면 등록을 거부한다.
 *  - 외부 URL·네트워크 명령·프롬프트 인젝션 의심 문구는 거부하지 않고 위험 플래그로 남겨 관리자 검토에 쓴다.
 */
@Component
public class SkillPackageReader {
    public static final int MAX_MD_BYTES = 100_000;
    public static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ENTRIES = 50;
    public static final long MAX_ENTRY_BYTES = 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;
    public static final int MAX_RATIO = 100;

    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");
    static final Pattern VERSION = Pattern.compile("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}$");
    static final Pattern TAG = Pattern.compile("^[\\p{L}\\p{N}_\\- ]{1,30}$");

    static final Set<String> TEXT_EXT = Set.of("md", "txt", "json", "yaml", "yml", "csv", "tsv");
    static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");
    static final Set<String> EXEC_EXT = Set.of("sh", "bash", "zsh", "bat", "cmd", "ps1", "psm1", "py", "pyc", "js", "mjs", "cjs", "ts", "jar", "class",
            "exe", "dll", "so", "dylib", "msi", "vbs", "wsf", "rb", "pl", "php", "jsp", "war", "com", "scr", "lnk", "svg", "html", "htm");

    /** 인증정보로 보이는 패턴 — 발견 시 등록 거부(값 자체는 메시지에 포함하지 않음) */
    static final List<Pattern> SECRETS = List.of(
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"), Pattern.compile("sk-proj-[A-Za-z0-9_-]{20,}"), Pattern.compile("\\bsk-[A-Za-z0-9]{32,}"),
            Pattern.compile("AIza[0-9A-Za-z_-]{35}"), Pattern.compile("\\bAQ\\.[A-Za-z0-9_-]{30,}"), Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), Pattern.compile("ghp_[A-Za-z0-9]{36}"), Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"));

    /** 위험 플래그(비차단) */
    static final Map<String, Pattern> RISKS = new LinkedHashMap<>();
    static {
        RISKS.put("외부 URL 포함", Pattern.compile("https?://", Pattern.CASE_INSENSITIVE));
        RISKS.put("네트워크/원격 명령 언급(curl, wget, ssh 등)", Pattern.compile("\\b(curl|wget|netcat|ssh|scp|sftp|telnet)\\b", Pattern.CASE_INSENSITIVE));
        RISKS.put("시스템 명령/실행 구문 언급", Pattern.compile("(rm\\s+-rf|\\bsudo\\b|chmod\\s+\\d|powershell|cmd\\.exe|\\beval\\(|\\bexec\\()", Pattern.CASE_INSENSITIVE));
        RISKS.put("프롬프트 인젝션 의심 문구", Pattern.compile("(ignore\\s+(all\\s+|the\\s+)?(previous|prior|above)\\s+instructions|disregard\\s+.{0,20}instructions|이전\\s*(지시|명령|지침)[을를]?\\s*(모두\\s*)?무시|시스템\\s*프롬프트.{0,10}(출력|공개|알려))", Pattern.CASE_INSENSITIVE));
        RISKS.put("대용량 인코딩 문자열(base64 등)", Pattern.compile("[A-Za-z0-9+/]{200,}={0,2}"));
    }

    public static class InvalidSkillException extends Exception {
        public InvalidSkillException(String message) { super(message); }
    }

    public record Meta(String name, String description, String version, String category, List<String> tags, String icon, Map<String, Object> raw) {}

    public record Parsed(Meta meta, String skillMd, String packageKind, byte[] packageData, String fileListJson, List<String> riskFlags, String sha256) {}

    private final ObjectMapper mapper;

    public SkillPackageReader(ObjectMapper mapper) { this.mapper = mapper; }

    /** 파일명/시그니처로 MD 와 ZIP 을 구분한다. */
    public Parsed read(String filename, byte[] bytes) throws InvalidSkillException {
        if (bytes == null || bytes.length == 0) throw new InvalidSkillException("빈 파일입니다");
        String f = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean zipMagic = bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
        if (f.endsWith(".zip") || zipMagic) {
            if (!zipMagic) throw new InvalidSkillException("ZIP 파일이 아닙니다");
            return readZip(bytes);
        }
        if (!(f.endsWith(".md") || f.isEmpty())) throw new InvalidSkillException("SKILL.md 또는 ZIP 파일만 업로드할 수 있습니다");
        return readMarkdown(bytes);
    }

    public Parsed readMarkdown(byte[] bytes) throws InvalidSkillException {
        if (bytes.length > MAX_MD_BYTES) throw new InvalidSkillException("SKILL.md 가 너무 큽니다 (최대 " + MAX_MD_BYTES / 1000 + "KB)");
        rejectExecutableSignature("SKILL.md", bytes);
        String md = decodeUtf8("SKILL.md", bytes);
        Meta meta = parseFrontmatter(md);
        List<String> risks = scan(List.of(md), meta);
        return new Parsed(meta, md, "MD", bytes, "[{\"path\":\"SKILL.md\",\"size\":" + bytes.length + "}]", risks, sha256(bytes));
    }

    // ───────────────────────── ZIP ─────────────────────────

    private record Central(String name, long compSize, long size, boolean symlink, boolean encrypted, boolean dir) {}

    public Parsed readZip(byte[] zip) throws InvalidSkillException {
        if (zip.length > MAX_ZIP_BYTES) throw new InvalidSkillException("ZIP 파일이 너무 큽니다 (최대 " + MAX_ZIP_BYTES / 1024 / 1024 + "MB)");
        rejectNestedOrExecutable("ZIP", zip, true);
        List<Central> central = centralDirectory(zip);
        Set<String> declared = new HashSet<>();
        long declaredTotal = 0;
        for (Central c : central) {
            checkName(c.name());
            if (c.symlink()) throw new InvalidSkillException("심볼릭 링크가 포함되어 있습니다: " + safe(c.name()));
            if (c.encrypted()) throw new InvalidSkillException("암호화된 ZIP 은 지원하지 않습니다");
            if (c.dir()) continue;
            if (!declared.add(c.name())) throw new InvalidSkillException("중복된 항목이 있습니다: " + safe(c.name()));
            if (c.size() > MAX_ENTRY_BYTES) throw new InvalidSkillException("파일이 너무 큽니다(최대 1MB): " + safe(c.name()));
            if (c.compSize() > 0 && c.size() / Math.max(1, c.compSize()) > MAX_RATIO) throw new InvalidSkillException("압축률이 비정상적으로 높습니다(압축 폭탄 의심): " + safe(c.name()));
            declaredTotal += c.size();
            if (declaredTotal > MAX_TOTAL_BYTES) throw new InvalidSkillException("압축 해제 총 용량이 너무 큽니다(최대 10MB)");
        }
        if (declared.size() > MAX_ENTRIES) throw new InvalidSkillException("파일이 너무 많습니다(최대 " + MAX_ENTRIES + "개)");

        // 실제 스트림을 읽으며 "선언된 값"이 아니라 "실제 읽은 바이트"로 다시 제한한다
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                checkName(e.getName());
                byte[] data = readBounded(zin, MAX_ENTRY_BYTES, e.getName());
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new InvalidSkillException("압축 해제 총 용량이 너무 큽니다(최대 10MB)");
                if (files.put(e.getName(), data) != null) throw new InvalidSkillException("중복된 항목이 있습니다");
                if (files.size() > MAX_ENTRIES) throw new InvalidSkillException("파일이 너무 많습니다(최대 " + MAX_ENTRIES + "개)");
            }
        } catch (IOException e) {
            throw new InvalidSkillException("ZIP 파일을 읽을 수 없습니다");
        }
        if (!files.keySet().equals(declared)) throw new InvalidSkillException("ZIP 구조가 올바르지 않습니다(중앙 디렉터리와 본문 불일치)");

        // SKILL.md 위치: 루트 또는 단일 최상위 폴더 아래
        List<String> skillPaths = files.keySet().stream().filter(n -> n.equals("SKILL.md") || n.matches("[^/]+/SKILL\\.md")).toList();
        if (skillPaths.size() != 1) throw new InvalidSkillException(skillPaths.isEmpty() ? "ZIP 루트(또는 단일 폴더)에 SKILL.md 가 없습니다" : "SKILL.md 가 여러 개입니다");
        String skillPath = skillPaths.get(0);
        String root = skillPath.contains("/") ? skillPath.substring(0, skillPath.indexOf('/') + 1) : "";
        if (!root.isEmpty()) for (String n : files.keySet()) if (!n.startsWith(root)) throw new InvalidSkillException("모든 파일은 하나의 폴더 아래에 있어야 합니다");

        List<String> texts = new ArrayList<>();
        List<Map<String, Object>> list = new ArrayList<>();
        String md = null;
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            String name = en.getKey();
            byte[] data = en.getValue();
            String ext = ext(name);
            if (EXEC_EXT.contains(ext)) throw new InvalidSkillException("실행 가능하거나 위험한 파일 형식은 허용되지 않습니다: " + safe(name));
            if (!TEXT_EXT.contains(ext) && !IMAGE_EXT.contains(ext)) throw new InvalidSkillException("허용되지 않는 파일 형식입니다: " + safe(name));
            rejectNestedOrExecutable(name, data, false);
            if (TEXT_EXT.contains(ext)) {
                String text = decodeUtf8(name, data);
                texts.add(text);
                if (name.equals(skillPath)) md = text;
            }
            list.add(Map.of("path", name.substring(root.length()), "size", data.length));
        }
        if (md == null) throw new InvalidSkillException("SKILL.md 를 읽을 수 없습니다");
        if (files.get(skillPath).length > MAX_MD_BYTES) throw new InvalidSkillException("SKILL.md 가 너무 큽니다");
        Meta meta = parseFrontmatter(md);
        List<String> risks = scan(texts, meta);
        try {
            return new Parsed(meta, md, "ZIP", zip, mapper.writeValueAsString(list), risks, sha256(zip));
        } catch (IOException e) {
            throw new InvalidSkillException("파일 목록을 만들 수 없습니다");
        }
    }

    /** ZIP 중앙 디렉터리 직접 파싱: java.util.zip 이 노출하지 않는 심볼릭 링크/암호화 정보를 읽는다. */
    private List<Central> centralDirectory(byte[] z) throws InvalidSkillException {
        ByteBuffer b = ByteBuffer.wrap(z).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = z.length - 22; i >= Math.max(0, z.length - 22 - 65535); i--) {
            if (b.getInt(i) == 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw new InvalidSkillException("올바른 ZIP 파일이 아닙니다");
        int count = Short.toUnsignedInt(b.getShort(eocd + 10));
        long cdSize = Integer.toUnsignedLong(b.getInt(eocd + 12));
        long cdOff = Integer.toUnsignedLong(b.getInt(eocd + 16));
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) throw new InvalidSkillException("ZIP64 는 지원하지 않습니다");
        if (count > MAX_ENTRIES * 2) throw new InvalidSkillException("파일이 너무 많습니다(최대 " + MAX_ENTRIES + "개)");
        if (cdOff + cdSize > z.length) throw new InvalidSkillException("ZIP 구조가 손상되었습니다");
        List<Central> out = new ArrayList<>();
        int p = (int) cdOff;
        for (int i = 0; i < count; i++) {
            if (p + 46 > z.length || b.getInt(p) != 0x02014b50) throw new InvalidSkillException("ZIP 중앙 디렉터리가 손상되었습니다");
            int madeBy = Short.toUnsignedInt(b.getShort(p + 4));
            int flags = Short.toUnsignedInt(b.getShort(p + 8));
            long comp = Integer.toUnsignedLong(b.getInt(p + 20));
            long size = Integer.toUnsignedLong(b.getInt(p + 24));
            int nameLen = Short.toUnsignedInt(b.getShort(p + 28));
            int extraLen = Short.toUnsignedInt(b.getShort(p + 30));
            int commentLen = Short.toUnsignedInt(b.getShort(p + 32));
            long external = Integer.toUnsignedLong(b.getInt(p + 38));
            if (p + 46 + nameLen > z.length) throw new InvalidSkillException("ZIP 중앙 디렉터리가 손상되었습니다");
            String name = new String(z, p + 46, nameLen, StandardCharsets.UTF_8);
            boolean unix = (madeBy >> 8) == 3;
            long mode = external >>> 16;
            boolean symlink = unix && (mode & 0170000) == 0120000;
            out.add(new Central(name, comp, size, symlink, (flags & 1) != 0, name.endsWith("/")));
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    private static void checkName(String n) throws InvalidSkillException {
        if (n == null || n.isEmpty() || n.length() > 200) throw new InvalidSkillException("파일 경로가 올바르지 않습니다");
        if (n.startsWith("/") || n.contains("\\") || n.contains(":") || n.chars().anyMatch(c -> c < 0x20 || c == 0x7f))
            throw new InvalidSkillException("허용되지 않는 파일 경로입니다: " + safe(n));
        for (String seg : n.split("/")) if (seg.equals("..") || seg.equals(".")) throw new InvalidSkillException("경로 탐색 시도가 감지되었습니다: " + safe(n));
    }

    private static byte[] readBounded(InputStream in, long limit, String name) throws IOException, InvalidSkillException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) throw new InvalidSkillException("파일이 너무 큽니다(최대 1MB): " + safe(name));
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ───────────────────────── 공통 ─────────────────────────

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 || i == name.length() - 1 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        String t = s.replaceAll("[\\p{Cntrl}]", "?");
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    /** 실행 파일/중첩 압축 시그니처 검사 (확장자를 속인 경우 방어) */
    private static void rejectNestedOrExecutable(String name, byte[] d, boolean outerZip) throws InvalidSkillException {
        if (d.length >= 2 && d[0] == 'M' && d[1] == 'Z') throw new InvalidSkillException("실행 파일이 포함되어 있습니다: " + safe(name));
        if (d.length >= 4 && d[0] == 0x7f && d[1] == 'E' && d[2] == 'L' && d[3] == 'F') throw new InvalidSkillException("실행 파일이 포함되어 있습니다: " + safe(name));
        if (d.length >= 4 && (d[0] & 0xff) == 0xCA && (d[1] & 0xff) == 0xFE && (d[2] & 0xff) == 0xBA && (d[3] & 0xff) == 0xBE) throw new InvalidSkillException("실행 파일이 포함되어 있습니다: " + safe(name));
        if (d.length >= 2 && d[0] == '#' && d[1] == '!') throw new InvalidSkillException("스크립트(shebang)가 포함되어 있습니다: " + safe(name));
        if (!outerZip && d.length >= 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4) throw new InvalidSkillException("중첩된 압축 파일은 허용되지 않습니다: " + safe(name));
    }

    private static void rejectExecutableSignature(String name, byte[] d) throws InvalidSkillException { rejectNestedOrExecutable(name, d, false); }

    private static String decodeUtf8(String name, byte[] data) throws InvalidSkillException {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            if (s.indexOf('\0') >= 0) throw new InvalidSkillException("텍스트 파일이 아닙니다: " + safe(name));
            if (s.startsWith("﻿")) s = s.substring(1);
            return s;
        } catch (CharacterCodingException e) {
            throw new InvalidSkillException("UTF-8 텍스트 파일이 아닙니다: " + safe(name));
        }
    }

    static String sha256(byte[] d) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** YAML Frontmatter 파싱 (SafeConstructor + 제한). 본문은 별도로 보존하므로 여기서는 메타데이터만 추출한다. */
    @SuppressWarnings("unchecked")
    Meta parseFrontmatter(String md) throws InvalidSkillException {
        String text = md.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) throw new InvalidSkillException("SKILL.md 는 '---' 로 시작하는 YAML Frontmatter(name, description)가 필요합니다");
        int end = text.indexOf("\n---", 4);
        if (end < 0) throw new InvalidSkillException("Frontmatter 의 종료 '---' 를 찾을 수 없습니다");
        String yamlText = text.substring(4, end);
        if (yamlText.length() > 8000) throw new InvalidSkillException("Frontmatter 가 너무 큽니다");
        Object parsed;
        try {
            LoaderOptions opt = new LoaderOptions();
            opt.setAllowDuplicateKeys(false);
            opt.setMaxAliasesForCollections(5);
            opt.setNestingDepthLimit(6);
            opt.setCodePointLimit(10_000);
            parsed = new Yaml(new SafeConstructor(opt)).load(yamlText);
        } catch (Exception e) {
            throw new InvalidSkillException("Frontmatter YAML 형식이 올바르지 않습니다");
        }
        if (!(parsed instanceof Map<?, ?> m)) throw new InvalidSkillException("Frontmatter 는 key: value 형식이어야 합니다");
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) map.put(String.valueOf(e.getKey()), e.getValue());

        String name = str(map.get("name"));
        if (name == null || !NAME.matcher(name).matches()) throw new InvalidSkillException("name 은 영문 소문자·숫자·하이픈 2~63자여야 합니다 (예: error-detective)");
        String desc = str(map.get("description"));
        if (desc == null || desc.isBlank()) throw new InvalidSkillException("description 이 필요합니다");
        desc = desc.strip().replaceAll("\\s+", " ");
        if (desc.length() > 1000) throw new InvalidSkillException("description 은 1000자 이하여야 합니다");
        String version = str(map.get("version"));
        if (version != null && !VERSION.matcher(version).matches()) throw new InvalidSkillException("version 은 1.0.0 형식이어야 합니다");
        String category = str(map.get("category"));
        String icon = str(map.get("icon"));
        if (icon != null && icon.codePointCount(0, icon.length()) > 4) throw new InvalidSkillException("icon 은 이모지 등 4자 이하여야 합니다");
        List<String> tags = new ArrayList<>();
        Object t = map.get("tags");
        if (t instanceof List<?> l) for (Object o : l) { String s = str(o); if (s != null) tags.add(s.strip()); }
        else if (t instanceof String s && !s.isBlank()) for (String x : s.split(",")) tags.add(x.strip());
        if (tags.size() > 10) throw new InvalidSkillException("tags 는 최대 10개입니다");
        for (String tag : tags) if (!TAG.matcher(tag).matches()) throw new InvalidSkillException("tag 형식이 올바르지 않습니다: " + safe(tag));

        // 알려진 스칼라 값만 보존(원문 전체는 SKILL.md 로 보존)
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Number || v instanceof Boolean) raw.put(e.getKey(), v);
            else if (v instanceof List<?> l && l.stream().allMatch(x -> x instanceof String || x instanceof Number)) raw.put(e.getKey(), l);
        }
        return new Meta(name, desc, version, category, tags, icon, raw);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** 인증정보는 거부, 나머지는 위험 플래그로 수집 */
    private List<String> scan(List<String> texts, Meta meta) throws InvalidSkillException {
        List<String> flags = new ArrayList<>();
        for (String t : texts) for (Pattern p : SECRETS)
            if (p.matcher(t).find()) throw new InvalidSkillException("인증정보(API 키·개인키 등)로 보이는 문자열이 포함되어 있습니다. 제거한 뒤 다시 등록하세요");
        for (Map.Entry<String, Pattern> r : RISKS.entrySet())
            for (String t : texts) if (r.getValue().matcher(t).find()) { flags.add(r.getKey()); break; }
        if (meta.raw().containsKey("allowed-tools")) flags.add("도구 사용 선언(allowed-tools) 포함 — AI HUB 는 도구를 실행하지 않음");
        return flags;
    }
}
