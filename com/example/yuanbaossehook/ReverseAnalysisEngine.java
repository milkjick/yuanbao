package com.example.yuanbaossehook;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Evidence-first APK analysis layer. It never invents source code: findings are derived
 * from bytes that actually exist in the APK or from successful external tool execution.
 */
final class ReverseAnalysisEngine {
    private ReverseAnalysisEngine() {}

    static JSONObject analyze(File apk, int maxStrings, int maxFindings) throws Exception {
        if (apk == null || !apk.isFile()) throw new FileNotFoundException("APK 不存在");
        JSONArray urls = new JSONArray(), domains = new JSONArray(), crypto = new JSONArray(), interesting = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        int dexCount=0; long total=0;
        Pattern urlPat=Pattern.compile("https?://[^\\s\\\"'<>\\\\]{4,512}", Pattern.CASE_INSENSITIVE);
        Pattern domainPat=Pattern.compile("\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}\\b", Pattern.CASE_INSENSITIVE);
        Pattern keyPat=Pattern.compile("(?i)(api[_-]?key|access[_-]?token|authorization|bearer|client[_-]?secret|secret[_-]?key|password|private[_-]?key|aes|rsa|cipher|encrypt|decrypt|base64|sha-?256|md5)");
        try (ZipFile z=new ZipFile(apk,ZipFile.OPEN_READ)) {
            Enumeration<? extends ZipEntry> en=z.entries();
            while(en.hasMoreElements()) {
                ZipEntry e=en.nextElement(); if(e.isDirectory()) continue; total++;
                String n=e.getName();
                if(n.matches("classes(\\d+)?\\.dex")) dexCount++;
                if((n.startsWith("classes")&&n.endsWith(".dex")) || n.endsWith(".xml") || n.startsWith("assets/") || n.startsWith("res/raw/")) {
                    long lim=Math.min(Math.max(0,e.getSize()), 8L*1024*1024); if(lim<=0) continue;
                    InputStream in=z.getInputStream(e); ByteArrayOutputStream b=new ByteArrayOutputStream((int)Math.min(lim,262144));
                    byte[] buf=new byte[8192]; int r; long got=0; while(got<lim&&(r=in.read(buf))>0){int take=(int)Math.min(r,lim-got);b.write(buf,0,take);got+=take;} in.close();
                    String text=new String(b.toByteArray(),StandardCharsets.ISO_8859_1);
                    Matcher m=urlPat.matcher(text); while(m.find()&&urls.length()<maxStrings){String s=clean(m.group());if(seen.add("u:"+s))urls.put(new JSONObject().put("value",s).put("entry",n).put("evidence","raw-bytes"));}
                    Matcher d=domainPat.matcher(text); while(d.find()&&domains.length()<maxStrings){String s=d.group();if(s.length()>3&&seen.add("d:"+s))domains.put(new JSONObject().put("value",s).put("entry",n).put("evidence","raw-bytes"));}
                    Matcher k=keyPat.matcher(text); while(k.find()&&interesting.length()<maxFindings){String s=k.group();interesting.put(new JSONObject().put("keyword",s).put("entry",n).put("evidence","raw-bytes"));}
                }
                String low=n.toLowerCase(Locale.ROOT);
                if(low.contains("lib/")&&low.endsWith(".so")) interesting.put(new JSONObject().put("keyword","native-library").put("entry",n).put("evidence","zip-entry"));
                if(low.contains("classes")&&low.endsWith(".dex")) {
                    String ln=low;
                    if(ln.contains("encrypt")||ln.contains("crypto")||ln.contains("security")) crypto.put(new JSONObject().put("entry",n).put("reason","DEX filename contains security/crypto marker").put("evidence","zip-entry"));
                }
            }
        }
        return new JSONObject().put("ok",true).put("apk",apk.getCanonicalPath()).put("sha256",sha256(apk))
                .put("zipEntries",total).put("dexCount",dexCount).put("urls",urls).put("domains",domains)
                .put("cryptoIndicators",crypto).put("interestingIndicators",interesting)
                .put("limits",new JSONObject().put("maxStrings",maxStrings).put("maxFindings",maxFindings));
    }
    private static String clean(String s){return s.replace("\\u0000","").replace("\\n","").replace("\\r","").trim();}
    private static String sha256(File f) throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(f);byte[] b=new byte[32768];int n;try{while((n=in.read(b))>0)md.update(b,0,n);}finally{in.close();}StringBuilder x=new StringBuilder();for(byte q:md.digest())x.append(String.format(Locale.ROOT,"%02x",q));return x.toString();}
}
