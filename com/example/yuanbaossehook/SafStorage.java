package com.example.yuanbaossehook;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real SAF-backed workspace adapter. No fake paths: the user must explicitly mount a tree. */
final class SafStorage {
    private static final String PREF = "yb_saf_workspace";
    private static final String KEY_URI = "tree_uri";
    private SafStorage() {}

    static void saveTree(Context c, Uri uri, int flags) throws Exception {
        if (c == null || uri == null) throw new IOException("SAF URI 为空");
        int take = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (take == 0) take = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        c.getContentResolver().takePersistableUriPermission(uri, take);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_URI, uri.toString()).apply();
    }
    static Uri tree(Context c) {
        if (c == null) return null;
        String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_URI, "");
        if (s == null || s.isEmpty()) return null;
        try { return Uri.parse(s); } catch (Throwable e) { return null; }
    }
    static boolean mounted(Context c) { return tree(c) != null; }
    static String status(Context c) { Uri u=tree(c); return u==null?"未挂载":"已挂载: "+u; }

    static String normalizeRelative(String p) throws IOException {
        if (p == null) p = "";
        p = p.trim().replace('\\','/');
        while (p.startsWith("/")) p=p.substring(1);
        while (p.contains("//")) p=p.replace("//","/");
        if (p.isEmpty() || ".".equals(p)) return "";
        String[] parts=p.split("/"); StringBuilder out=new StringBuilder();
        for(String part:parts){
            if(part.isEmpty() || ".".equals(part)) continue;
            if("..".equals(part)) throw new IOException("SAF 路径越界");
            if(out.length()>0) out.append('/'); out.append(part);
        }
        return out.toString();
    }
    static Uri child(Context c, String relative) throws Exception {
        Uri root=tree(c); if(root==null) throw new IOException("尚未通过 SAF 挂载工作目录");
        String rel=normalizeRelative(relative); if(rel.isEmpty()) return root;
        Uri cur=root;
        for(String name:rel.split("/")) {
            String id=DocumentsContract.getTreeDocumentId(cur);
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(cur,id);
            String found=null;
            Cursor q=null;
            try {
                q=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME+"=?",new String[]{name},null);
                if(q!=null && q.moveToFirst()) found=q.getString(0);
            } finally { if(q!=null) q.close(); }
            if(found==null) return null;
            cur=DocumentsContract.buildDocumentUriUsingTree(root,found);
        }
        return cur;
    }
    static Uri childOrThrow(Context c,String relative) throws Exception { Uri u=child(c,relative); if(u==null) throw new FileNotFoundException("SAF 文件不存在: "+relative); return u; }

    static org.json.JSONArray list(Context c,String relative) throws Exception {
        Uri dir=childOrThrow(c,relative); org.json.JSONArray arr=new org.json.JSONArray();
        String id=DocumentsContract.getDocumentId(dir); Uri tree=tree(c);
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
        Cursor q=null;
        try {
            q=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,DocumentsContract.Document.COLUMN_DISPLAY_NAME+" COLLATE NOCASE ASC");
            if(q!=null) while(q.moveToNext()) {
                String name=q.getString(1); int mime=q.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                long size=q.isNull(3)?0:q.getLong(3); long mod=q.isNull(4)?0:q.getLong(4);
                boolean isDir = mime>=0 && DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(mime));
                arr.put(new org.json.JSONObject().put("name",name).put("directory",isDir).put("sizeBytes",size).put("lastModified",mod).put("path",(relative==null||relative.isEmpty()?name:relative+"/"+name)));
            }
        } finally { if(q!=null)q.close(); }
        return arr;
    }
    static byte[] read(Context c,String relative,int max,long offset) throws Exception {
        Uri u=childOrThrow(c,relative); ParcelFileDescriptor pfd=c.getContentResolver().openFileDescriptor(u,"r"); if(pfd==null)throw new IOException("无法打开 SAF 文件");
        FileInputStream in=new FileInputStream(pfd.getFileDescriptor());
        try { long skip=offset; while(skip>0){long n=in.skip(skip);if(n<=0)break;skip-=n;} ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int total=0,n; while(total<max&&(n=in.read(buf))>0){int take=Math.min(n,max-total);b.write(buf,0,take);total+=take;if(take<n)break;}return b.toByteArray(); }
        finally { try{in.close();}catch(Throwable ignored){} try{pfd.close();}catch(Throwable ignored){} }
    }
    static Uri mkdir(Context c,String relative) throws Exception {
        String rel=normalizeRelative(relative); if(rel.isEmpty())return tree(c); Uri existing=child(c,rel); if(existing!=null)return existing;
        int slash=rel.lastIndexOf('/'); String parent=slash<0?"":rel.substring(0,slash); String name=slash<0?rel:rel.substring(slash+1);
        Uri p=childOrThrow(c,parent); String pid=DocumentsContract.getDocumentId(p); Uri u=DocumentsContract.buildDocumentUriUsingTree(tree(c),pid);
        Uri made=DocumentsContract.createDocument(c.getContentResolver(),u,DocumentsContract.Document.MIME_TYPE_DIR,name); if(made==null)throw new IOException("SAF 创建目录失败: "+rel); return made;
    }
    static Uri write(Context c,String relative,byte[] data) throws Exception {
        String rel=normalizeRelative(relative); if(rel.isEmpty())throw new IOException("不能写 SAF 根目录");
        Uri existing=child(c,rel); Uri u=existing;
        if(u==null){int slash=rel.lastIndexOf('/');String parent=slash<0?"":rel.substring(0,slash);String name=slash<0?rel:rel.substring(slash+1);Uri p=mkdir(c,parent);u=DocumentsContract.createDocument(c.getContentResolver(),p,"text/plain",name);if(u==null)throw new IOException("SAF 创建文件失败");}
        ParcelFileDescriptor pfd=c.getContentResolver().openFileDescriptor(u,"wt"); if(pfd==null)throw new IOException("SAF 无法写入"); FileOutputStream out=new FileOutputStream(pfd.getFileDescriptor()); try{out.write(data);out.flush();}finally{try{out.close();}catch(Throwable ignored){}try{pfd.close();}catch(Throwable ignored){}} return u;
    }
    static Uri copy(Context c,String src,String dst) throws Exception { byte[] b=read(c,src,16*1024*1024,0); return write(c,dst,b); }
}