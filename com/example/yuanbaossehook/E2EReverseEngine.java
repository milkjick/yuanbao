package com.example.yuanbaossehook;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Real-device reverse -> optional patch -> rebuild -> sign -> install -> logcat loop.
 * Every external action is executed through the gateway's real shell executor; unavailable
 * toolchains are reported instead of being simulated.
 */
final class E2EReverseEngine {
    private static final Object LOCK = new Object();
    private static final Map<String,Thread> WORKERS = new HashMap<>();
    private static final int MAX_CYCLES = 8;

    static JSONObject start(JSONObject a) throws Exception {
        if (a == null) a = new JSONObject();
        String apk=a.optString("apk","").trim();
        if(apk.isEmpty()) throw new IllegalArgumentException("apk 不能为空");
        File f=new File(apk).getCanonicalFile();
        if(!f.isFile()) throw new FileNotFoundException("APK 不存在: "+f);
        String name=a.optString("name","").trim();
        if(name.isEmpty()) name=f.getName().replaceAll("(?i)\\.apk$","");
        String project=a.optString("project","").trim();
        if(project.isEmpty()) project=new File(McpIdeGateway.MCP_ROOT_PUBLIC(),"projects/"+safe(name)).getCanonicalPath();
        JSONObject args=new JSONObject(a.toString()).put("apk",f.getCanonicalPath()).put("name",name).put("project",project);
        String id=McpTaskStore.create("agent_e2e",args,48L*60*60*1000L,1500L);
        synchronized(LOCK){
            Thread t=new Thread(()->run(id,args),"YB-E2E-"+id.substring(0,8)); WORKERS.put(id,t); t.start();
        }
        try{KeepAliveService.start(McpIdeGateway.applicationContextPublic());}catch(Throwable ignored){}
        return new JSONObject().put("ok",true).put("resultType","task").put("taskId",id).put("project",project)
                .put("mode","real-device-e2e").put("pipeline",new JSONArray().put("inspect").put("decompile").put("patch").put("rebuild").put("sign").put("install").put("logcat").put("feedback"));
    }

    static JSONObject status(JSONObject a)throws Exception{return McpTaskStore.get(a.optString("taskId",""));}
    static JSONObject cancel(JSONObject a)throws Exception{String id=a.optString("taskId","");McpTaskStore.cancel(id);synchronized(LOCK){Thread t=WORKERS.get(id);if(t!=null)t.interrupt();}return McpTaskStore.get(id);}

    private static void run(String id, JSONObject a){
        String worker="e2e-"+UUID.randomUUID();
        try{
            if(!McpTaskStore.claimWorker(id,worker))return;
            File project=new File(a.getString("project")).getCanonicalFile(); if(!project.exists()&&!project.mkdirs())throw new IOException("无法创建项目: "+project);
            File input=new File(project,"input"), output=new File(project,"output"), report=new File(project,"report"); input.mkdirs();output.mkdirs();report.mkdirs();
            JSONObject state=loadState(project); state.put("taskId",id).put("apk",a.getString("apk")).put("project",project.getCanonicalPath()).put("updatedAt",System.currentTimeMillis());
            saveState(project,state);
            File apk=new File(a.getString("apk"));
            stage(id,worker,state,5,"inspect","正在读取 APK 真实信息");
            JSONObject overview=McpIdeGateway.callToolDirect("local_apk_analyze",new JSONObject().put("path",apk.getCanonicalPath()).put("query",a.optString("query","")));
            write(project,"input/overview.json",overview); state.put("overview",overview); state.put("sha256",sha256(apk)); saveState(project,state);
            check(id);

            String decomp=a.optString("decompileTool","");
            File decompiled=new File(output,"decompiled");
            stage(id,worker,state,18,"decompile","尝试真实 JADX/apktool 反编译");
            JSONObject open=McpIdeGateway.callToolDirect("apk_open",new JSONObject().put("locator",apk.getCanonicalPath()).put("temporary",true));
            String handle=open.optString("handle",""); JSONObject dec;
            if(handle.isEmpty()) throw new IOException("apk_open 未返回 handle: "+open);
            try{dec=McpIdeGateway.callToolDirect("apk_decompile",new JSONObject().put("handle",handle).put("output",decompiled.getCanonicalPath()).put("tool",decomp));}
            finally{try{McpIdeGateway.callToolDirect("apk_close",new JSONObject().put("handle",handle));}catch(Throwable ignored){}}
            write(project,"input/decompile.json",dec); state.put("decompile",dec); saveState(project,state);
            if(!dec.optBoolean("ok",false)) throw new IOException("反编译工具不可用或执行失败: "+dec.optString("error",dec.optString("reason","unknown")));
            check(id);

            JSONArray patches=a.optJSONArray("patches");
            stage(id,worker,state,32,"patch","应用用户提供的确定性补丁");
            JSONObject patchResult=applyPatches(decompiled,patches); write(project,"input/patch.json",patchResult);state.put("patch",patchResult);saveState(project,state);
            check(id);

            stage(id,worker,state,48,"rebuild","真实重新打包 APK");
            File unsigned=new File(output,"rebuilt-unsigned.apk");
            JSONObject rebuild=runShell("apktool b "+q(decompiled.getCanonicalPath())+" -o "+q(unsigned.getCanonicalPath()),project,180000);
            if(!rebuild.optBoolean("ok",false)) throw new IOException("apktool rebuild 失败: "+rebuild);
            state.put("rebuild",rebuild); write(project,"input/rebuild.json",rebuild); saveState(project,state);
            check(id);

            stage(id,worker,state,62,"sign","使用可用 apksigner/keystore 真实签名");
            File signed=new File(output,"rebuilt-signed.apk");
            JSONObject sign=sign(unsigned,signed,project); if(!sign.optBoolean("ok",false)) throw new IOException("APK 签名失败: "+sign);
            state.put("sign",sign).put("signedApk",signed.getCanonicalPath()); write(project,"input/sign.json",sign);saveState(project,state);
            check(id);

            stage(id,worker,state,74,"install","通过真实 ADB/Shizuku 安装签名 APK");
            JSONObject install=runShell("adb install -r "+q(signed.getCanonicalPath()),project,120000);
            state.put("install",install);write(project,"input/install.json",install);saveState(project,state);
            if(!install.optBoolean("ok",false)) throw new IOException("ADB install 失败: "+install);
            check(id);

            int cycles=Math.max(1,Math.min(MAX_CYCLES,a.optInt("maxCycles",3)));
            String pkg=overview.optString("packageName","");
            JSONObject lastLog=new JSONObject();
            for(int cycle=0;cycle<cycles;cycle++){
                stage(id,worker,state,80,"runtime_cycle_"+cycle,"启动应用并采集 Logcat");
                JSONObject launch=launchPackage(pkg,project); state.put("launch",launch);
                lastLog=runShell("logcat -c; sleep 1; logcat -d -v threadtime",project,30000);
                state.put("logcat",lastLog).put("cycle",cycle); write(project,"input/logcat-"+cycle+".json",lastLog);saveState(project,state);
                JSONObject feedback=analyzeRuntime(lastLog,pkg);
                state.put("runtimeFeedback",feedback);write(project,"input/runtime-feedback-"+cycle+".json",feedback);saveState(project,state);
                if(!feedback.optBoolean("actionable",false)) break;
                JSONArray repairs=a.optJSONArray("runtimeRepairs");
                if(repairs==null||repairs.length()==0) break;
                JSONObject applied=applyPatches(decompiled,repairs);state.put("runtimePatch"+cycle,applied);saveState(project,state);
                if(!applied.optBoolean("ok",false)) break;
                JSONObject rb=runShell("apktool b "+q(decompiled.getCanonicalPath())+" -o "+q(unsigned.getCanonicalPath()),project,180000);if(!rb.optBoolean("ok",false))break;
                JSONObject sg=sign(unsigned,signed,project);if(!sg.optBoolean("ok",false))break;
                JSONObject ins=runShell("adb install -r "+q(signed.getCanonicalPath()),project,120000);if(!ins.optBoolean("ok",false))break;
            }
            stage(id,worker,state,96,"report","写入完整端到端报告");
            writeReport(project,state);
            stage(id,worker,state,100,"completed","真实设备端到端流程完成");
            McpTaskStore.checkpoint(id,new JSONObject().put("state",state).put("completed",true));
            McpTaskStore.complete(id,new JSONObject().put("ok",true).put("project",project.getCanonicalPath()).put("signedApk",state.optString("signedApk","")).put("runtimeCycles",state.optInt("cycle",0)+1));
        }catch(InterruptedException e){try{McpTaskStore.progress(id,"E2E worker 已暂停，checkpoint 已保存",-1,"resume");}catch(Throwable ignored){}}
         catch(Throwable e){try{McpTaskStore.fail(id,String.valueOf(e));}catch(Throwable ignored){}}
        finally{synchronized(LOCK){WORKERS.remove(id);}}
    }

    private static JSONObject sign(File unsigned,File signed,File project)throws Exception{
        String apksigner=findTool("apksigner");
        if(apksigner.isEmpty())return new JSONObject().put("ok",false).put("available",false).put("error","未发现 apksigner");
        String keytool=findTool("keytool"); File ks=new File(project,"output/debug.keystore");
        if(!ks.isFile()){
            if(keytool.isEmpty())return new JSONObject().put("ok",false).put("available",false).put("error","未发现 keytool，无法生成签名密钥");
            JSONObject gen=runShell(q(keytool)+" -genkeypair -keystore "+q(ks.getCanonicalPath())+" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname \"CN=Android Debug,O=Android,C=US\" -noprompt",project,30000);if(!gen.optBoolean("ok",false))return gen;
        }
        return runShell(q(apksigner)+" sign --ks "+q(ks.getCanonicalPath())+" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "+q(signed.getCanonicalPath())+" "+q(unsigned.getCanonicalPath()),project,120000);
    }
    private static String findTool(String n){
        String[] roots={"/system/bin/"}; try{if(McpIdeGateway.applicationContextPublic()!=null){File b=McpIdeGateway.applicationContextPublic().getFilesDir();roots=new String[]{b.getAbsolutePath()+"/bin/",b.getAbsolutePath()+"/runtime/bin/",b.getAbsolutePath()+"/toolchain/bin/"};}}catch(Throwable ignored){}
        for(String r:roots){File f=new File(r,n);if(f.isFile()&&f.canExecute())return f.getAbsolutePath();}
        try{JSONObject p=runShell("command -v "+n, null,5000);if(p.optBoolean("ok",false)){String x=p.optString("stdout","").trim();if(!x.isEmpty())return x.split("\\s+")[0];}}catch(Throwable ignored){}
        return "";
    }
    private static JSONObject launchPackage(String pkg,File cwd)throws Exception{if(pkg==null||pkg.isEmpty())return new JSONObject().put("ok",false).put("error","Manifest packageName 为空");return runShell("monkey -p "+q(pkg)+" 1",cwd,15000);}
    private static JSONObject runShell(String cmd,File cwd,long timeout)throws Exception{return McpIdeGateway.shellExecPublic(new JSONObject().put("command",cmd).put("cwd",cwd==null?"":cwd.getCanonicalPath()).put("timeoutMs",timeout));}
    private static JSONObject analyzeRuntime(JSONObject r,String pkg) throws JSONException {
        String all=(r.optString("stdout","")+"\n"+r.optString("stderr","")).toLowerCase(Locale.ROOT);
        JSONArray hits=new JSONArray();
        String[] keys={"fatal exception","androidruntime","force close","process "+pkg.toLowerCase(Locale.ROOT)+"","exception","securityexception","verifyerror","nosuchmethoderror","classnotfoundexception","resources.notfoundexception"};
        for(String k:keys)if(all.contains(k))hits.put(k);
        return new JSONObject().put("actionable",hits.length()>0).put("package",pkg).put("indicators",hits)
                .put("note",hits.length()>0
                        ? "检测到真实运行异常；下一次迭代会依据 logcat 生成 runtimeRepairs 补丁。"
                        : "本次运行未发现明显异常崩溃特征。");
    }
    private static JSONObject applyPatches(File root,JSONArray patches)throws Exception{JSONObject out=new JSONObject().put("ok",true).put("count",0);if(patches==null)return out;for(int i=0;i<patches.length();i++){JSONObject p=patches.optJSONObject(i);if(p==null)continue;String rel=p.optString("path","");if(rel.isEmpty()||rel.contains("..")||rel.startsWith("/"))throw new IOException("非法 patch path: "+rel);File f=new File(root,rel).getCanonicalFile();String rp=root.getCanonicalPath();if(!f.getPath().startsWith(rp+File.separator))throw new IOException("patch 越界");String op=p.optString("op","replace");if("write".equals(op)){f.getParentFile().mkdirs();writeBytes(f,p.optString("content","").getBytes(StandardCharsets.UTF_8));}else if("delete".equals(op)){if(f.exists()&&!f.delete())throw new IOException("delete failed: "+rel);}else{String s=f.isFile()?new String(readBytes(f),StandardCharsets.UTF_8):"";String from=p.optString("from","");String to=p.optString("to","");if(from.isEmpty()||!s.contains(from))throw new IOException("replace target not found: "+rel);s=s.replace(from,to);writeBytes(f,s.getBytes(StandardCharsets.UTF_8));}out.put("count",out.optInt("count",0)+1);}return out;}
    private static void stage(String id,String worker,JSONObject s,int p,String next,String msg)throws Exception{check(id);McpTaskStore.heartbeat(id,worker);s.put("progress",p).put("next",next).put("statusMessage",msg).put("updatedAt",System.currentTimeMillis());McpTaskStore.checkpoint(id,new JSONObject().put("state",s));McpTaskStore.progress(id,msg,p,next);}
    private static void check(String id)throws InterruptedException{if(McpTaskStore.isCancelled(id))throw new InterruptedException("cancelled");if(Thread.currentThread().isInterrupted())throw new InterruptedException("interrupted");}
    private static JSONObject loadState(File p){try{File f=new File(p,"input/e2e-state.json");if(f.isFile())return new JSONObject(new String(readBytes(f),StandardCharsets.UTF_8));}catch(Throwable ignored){}return new JSONObject();}
    private static void saveState(File p,JSONObject s)throws Exception{write(p,"input/e2e-state.json",s);}
    private static void write(File p,String rel,JSONObject o)throws Exception{writeBytes(new File(p,rel),o.toString(2).getBytes(StandardCharsets.UTF_8));}
    private static void writeReport(File p,JSONObject s)throws Exception{StringBuilder b=new StringBuilder("# Real-device E2E Reverse Report\n\n");b.append("This report records only real tool executions and their returned results.\n\n");b.append("- APK: `").append(s.optString("apk","")).append("`\n- SHA-256: `").append(s.optString("sha256","")).append("`\n- Signed APK: `").append(s.optString("signedApk","")).append("`\n- Runtime cycles: ").append(s.optInt("cycle",0)+1).append("\n\n");b.append("## Runtime feedback\n\n").append(s.optJSONObject("runtimeFeedback")==null?"No feedback":"```json\n"+s.optJSONObject("runtimeFeedback").toString(2)+"\n```\n");writeBytes(new File(p,"report/e2e.md"),b.toString().getBytes(StandardCharsets.UTF_8));write(p,"report/state.json",s);}
    private static String sha256(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(f);byte[] b=new byte[65536];int n;while((n=in.read(b))>0)d.update(b,0,n);in.close();StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.US,"%02x",x&255));return s.toString();}
    private static String q(String x){return "'"+x.replace("'","'\\''")+"'";}
    private static String safe(String x){return x.replaceAll("[^A-Za-z0-9._-]","_");}
    private static byte[] readBytes(File f)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();InputStream i=new FileInputStream(f);byte[] b=new byte[65536];int n;while((n=i.read(b))>0)o.write(b,0,n);i.close();return o.toByteArray();}
    private static void writeBytes(File f,byte[] b)throws Exception{File par=f.getParentFile();if(par!=null)par.mkdirs();File tmp=new File(f.getPath()+".tmp");FileOutputStream o=new FileOutputStream(tmp);o.write(b);o.flush();o.close();if(!tmp.renameTo(f)){FileOutputStream q=new FileOutputStream(f);q.write(b);q.close();tmp.delete();}}
}