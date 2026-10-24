import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;

/** Checks retained mixin method instructions/annotations and JVM basic dataflow. */
public final class VerifyPatch {
 static ClassNode read(ZipFile z)throws IOException{ClassNode n=new ClassNode();new ClassReader(z.getInputStream(z.getEntry("com/logic/recruitstacz/mixin/MixinAbstractRecruitEntity.class"))).accept(n,0);return n;}
 static String text(MethodNode m){Textifier t=new Textifier();m.accept(new TraceMethodVisitor(t));StringWriter w=new StringWriter();t.print(new PrintWriter(w));return w.toString();}
 public static void main(String[] args)throws Exception{
  try(ZipFile original=new ZipFile(args[0]);ZipFile patched=new ZipFile(args[1])){
   ClassNode a=read(original),b=read(patched);Map<String,MethodNode> before=new HashMap<>();for(MethodNode m:a.methods)before.put(m.name+m.desc,m);
   int count=0;
   for(MethodNode m:b.methods){MethodNode previous=before.get(m.name+m.desc);if(previous==null)throw new AssertionError("Unexpected method "+m.name);if(!text(previous).equals(text(m)))throw new AssertionError("Changed retained method "+m.name);new Analyzer<BasicValue>(new BasicVerifier()).analyze(b.name,m);count++;}
   if(a.methods.size()-b.methods.size()!=6)throw new AssertionError("Unexpected method deletion count");
   if(b.methods.stream().noneMatch(m->m.name.equals("searchForTargetsSync")))throw new AssertionError("Live sync overwrite lost");
   if(!Arrays.equals(original.getInputStream(original.getEntry("recruitstacz.mixins.json")).readAllBytes(),patched.getInputStream(patched.getEntry("recruitstacz.mixins.json")).readAllBytes()))throw new AssertionError("Mixin registration changed");
   System.out.println("PASS: "+count+" retained methods have identical resolved instruction/annotation text; JVM basic dataflow passes; exactly 6 obsolete methods removed; mixin registration unchanged.");
  }
 }
}
