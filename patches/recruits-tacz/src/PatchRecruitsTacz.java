import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Minimal, pinned binary patch for upstream TACZ Recruits 1.022, MIT (LogicalMaximus).
 * Recruits 1.15.2 removed the unused asynchronous search path. Its live search wrapper
 * delegates to searchForTargetsSync(ServerLevel); all live sync/goal logic is retained.
 */
public final class PatchRecruitsTacz {
 static final String MIXIN="com/logic/recruitstacz/mixin/MixinAbstractRecruitEntity";
 static final String CLASS=MIXIN+".class";
 static final String ASYNC="searchForTargetsAsync";
 static final String SYNC="searchForTargetsSync";
 static final String DESC="(Lnet/minecraft/server/level/ServerLevel;)V";
 static final String ORIGINAL_SHA512="a85216fa65242cd0a3ba3d99c83c86d1076f09e32d553046ce294ef49304bbb187b69f3310970b31d3a8c5d1356de5a5e6fedf930d12bf662b7152d410a4f808";
 static final String VERSION="1.022-frontier.1";
 static final List<ZipFile> LIBS=new ArrayList<>();
 static final Map<String,ClassNode> CACHE=new HashMap<>();
 static final List<String> AUDIT=new ArrayList<>();
 static AnnotationNode annotation(List<AnnotationNode> list,String name){
  if(list!=null)for(AnnotationNode a:list)if(a.desc.equals("Lorg/spongepowered/asm/mixin/"+name+";"))return a;
  return null;
 }
 static boolean marked(MethodNode m,String n){return annotation(m.visibleAnnotations,n)!=null||annotation(m.invisibleAnnotations,n)!=null;}
 static Object value(AnnotationNode a,String key){if(a!=null&&a.values!=null)for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals(key))return a.values.get(i+1);return null;}
 static ClassNode parse(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
 static ClassNode get(String name)throws IOException{
  if(CACHE.containsKey(name))return CACHE.get(name);
  for(ZipFile z:LIBS){ZipEntry e=z.getEntry(name+".class");if(e!=null){ClassNode n=parse(z.getInputStream(e).readAllBytes());CACHE.put(name,n);return n;}}
  return null;
 }
 static String findMethod(String owner,String name,String desc,Set<String> seen)throws IOException{
  if(owner==null||!seen.add(owner))return null;
  ClassNode n=get(owner);if(n==null)return null;
  for(MethodNode m:n.methods)if(m.name.equals(name)&&m.desc.equals(desc))return owner;
  String r=findMethod(n.superName,name,desc,seen);if(r!=null)return r;
  for(String i:n.interfaces){r=findMethod(i,name,desc,seen);if(r!=null)return r;}
  return null;
 }
 static String sha(byte[] b,String algorithm)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(b));}
 static byte[] patch(byte[] original)throws Exception{
  ClassReader reader=new ClassReader(original);ClassNode node=parse(original);
  MethodNode sync=node.methods.stream().filter(m->m.name.equals(SYNC)&&m.desc.equals(DESC)).findFirst().orElseThrow();
  if(!marked(sync,"Overwrite"))throw new IllegalStateException("Expected active sync overwrite");
  if(node.methods.stream().filter(m->m.name.equals(ASYNC)&&m.desc.equals(DESC)&&marked(m,"Overwrite")).count()!=1)throw new IllegalStateException("Unexpected original async overwrite count");
  Set<String> removed=new HashSet<>();
  node.methods.removeIf(m->{boolean remove=m.name.equals(ASYNC)&&m.desc.equals(DESC)||m.name.startsWith("lambda$searchForTargetsAsync$");if(remove)removed.add(m.name+m.desc);return remove;});
  if(removed.size()!=6)throw new IllegalStateException("Expected obsolete method and 5 generated lambda helpers, found "+removed);
  // Fail if any retained method still calls a removed body or references it in a lambda.
  for(MethodNode m:node.methods)for(AbstractInsnNode ins:m.instructions){
   if(ins instanceof MethodInsnNode c&&c.owner.equals(MIXIN)&&removed.contains(c.name+c.desc))throw new IllegalStateException("Retained direct call to removed method "+m.name);
   if(ins instanceof InvokeDynamicInsnNode d)for(Object a:d.bsmArgs)if(a instanceof Handle h&&h.getOwner().equals(MIXIN)&&removed.contains(h.getName()+h.getDesc()))throw new IllegalStateException("Retained lambda handle to removed method "+m.name);
  }
  ClassWriter w=new ClassWriter(reader,0);node.accept(w);byte[] b=w.toByteArray();
  if(parse(b).methods.stream().anyMatch(m->m.name.equals(ASYNC)||m.name.startsWith("lambda$searchForTargetsAsync$")))throw new IllegalStateException("Patch postcondition failed");
  AUDIT.add("Removed only obsolete async overwrite and its 5 unreachable compiler-generated helpers; retained all sync, shooting, cover, suppressive fire, animation and ammo logic.");
  return b;
 }
 static void auditOverwrites(ZipFile addon,Map<String,byte[]> replacements)throws Exception{
  for(ZipEntry e:Collections.list(addon.entries())){
   if(!e.getName().startsWith("com/logic/recruitstacz/mixin/")||!e.getName().endsWith(".class"))continue;
   byte[] b=replacements.getOrDefault(e.getName(),addon.getInputStream(e).readAllBytes());ClassNode n=parse(b);
   AnnotationNode a=annotation(n.invisibleAnnotations,"Mixin");if(a==null)a=annotation(n.visibleAnnotations,"Mixin");if(a==null)continue;
   @SuppressWarnings("unchecked")List<Type> ts=(List<Type>)value(a,"value");if(ts==null)continue;
   // Only active upstream mixins containing overwrites. No class is removed from mixin config.
   for(MethodNode m:n.methods)if(marked(m,"Overwrite"))for(Type t:ts){
    String owner=findMethod(t.getInternalName(),m.name,m.desc,new HashSet<>());
    AUDIT.add("OVERWRITE "+n.name+"#"+m.name+m.desc+" -> "+(owner==null?"MISSING":owner));
    if(owner==null)throw new IllegalStateException("Unresolved overwrite "+n.name+" "+m.name+m.desc);
   }
  }
 }
 public static void main(String[] args)throws Exception{
  if(args.length<5)throw new IllegalArgumentException("input.jar output.jar LICENSE report.txt dependencies...");
  Path input=Path.of(args[0]),output=Path.of(args[1]);byte[] bytes=Files.readAllBytes(input);
  if(!sha(bytes,"SHA-512").equals(ORIGINAL_SHA512))throw new IllegalStateException("Input does not match pinned official JAR");
  for(int i=4;i<args.length;i++)LIBS.add(new ZipFile(args[i]));
  try(ZipFile zip=new ZipFile(input.toFile())){
   Map<String,byte[]> replacements=new LinkedHashMap<>();replacements.put(CLASS,patch(zip.getInputStream(zip.getEntry(CLASS)).readAllBytes()));
   String meta=new String(zip.getInputStream(zip.getEntry("META-INF/mods.toml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   if(!meta.contains("version = \"1.022\""))throw new IllegalStateException("Unexpected original metadata version");
   meta=meta.replace("version = \"1.022\"","version = \""+VERSION+"\"").replace("displayName = \"TACZ Recruits\"","displayName = \"TACZ Recruits (Frontier patch)\"");
   meta+="\n# Local patch is audited for exactly Recruits 1.15.2.\n[[dependencies.recruitstacz]]\nmodId = \"recruits\"\nmandatory = true\nversionRange = \"[1.15.2]\"\nordering = \"AFTER\"\nside = \"BOTH\"\n";
   replacements.put("META-INF/mods.toml",meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
   auditOverwrites(zip,replacements);
   Files.createDirectories(output.getParent());
   try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(output))){
    for(ZipEntry e:Collections.list(zip.entries())){
     if(e.isDirectory())continue;
     if(e.getName().matches("META-INF/[^/]+\\.(SF|RSA|DSA)"))throw new IllegalStateException("Signed original requires a different patch flow");
     ZipEntry copy=new ZipEntry(e.getName());copy.setTime(0);out.putNextEntry(copy);out.write(replacements.getOrDefault(e.getName(),zip.getInputStream(e).readAllBytes()));out.closeEntry();
    }
    for(String name:List.of("META-INF/LICENSE.frontier-tacz-recruits","META-INF/FRONTIER-PATCH.txt")){
     ZipEntry e=new ZipEntry(name);e.setTime(0);out.putNextEntry(e);
     out.write(name.contains("LICENSE")?Files.readAllBytes(Path.of(args[2])):("Local compatibility patch "+VERSION+" for Recruits 1.15.2.\nOriginal author: LogicalMaximus, MIT.\nRemoved the obsolete unused searchForTargetsAsync overwrite and 5 dead lambda helpers.\nRetains native sync targeting and full gun AI. Reproducible patch source is shipped with the pack.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
    }
   }
   AUDIT.add("Original SHA-512: "+sha(bytes,"SHA-512"));AUDIT.add("Patched SHA-512: "+sha(Files.readAllBytes(output),"SHA-512"));
   Files.writeString(Path.of(args[3]),String.join("\n",AUDIT)+"\n");for(String line:AUDIT)System.out.println(line);
  }finally{for(ZipFile z:LIBS)z.close();}
 }
}
