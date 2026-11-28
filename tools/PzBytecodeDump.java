import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 零依赖 JDK 25 字节码提取器：扫描 projectzomboid.jar 中全部 zombie/**\/*.class，
 * 导出类继承树、方法签名与精确方法调用边 TSV，供 tools/pz_index.py 导入只读 SQLite。
 * 可直接通过单文件源码启动：java tools/PzBytecodeDump.java <jarPath> <outDir>
 */
public class PzBytecodeDump {
    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        String jarPath = args.length > 0
                ? args[0]
                : "D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/projectzomboid.jar";
        Path outDir = Paths.get(args.length > 1 ? args[1] : ".cache");
        Files.createDirectories(outDir);

        int classCount = 0;
        int methodCount = 0;
        int parseErrors = 0;
        ClassFile cf = ClassFile.of();

        try (JarFile jar = new JarFile(jarPath);
             BufferedWriter clsW = Files.newBufferedWriter(outDir.resolve("classes.tsv"), StandardCharsets.UTF_8);
             BufferedWriter mthW = Files.newBufferedWriter(outDir.resolve("methods.tsv"), StandardCharsets.UTF_8);
             BufferedWriter calW = Files.newBufferedWriter(outDir.resolve("calls.tsv"), StandardCharsets.UTF_8)) {

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String entryName = e.getName();
                if (!entryName.startsWith("zombie/") || !entryName.endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream is = jar.getInputStream(e)) {
                    bytes = is.readAllBytes();
                }
                ClassModel cm;
                try {
                    cm = cf.parse(bytes);
                } catch (Throwable ex) {
                    parseErrors++;
                    continue;
                }
                classCount++;
                String clsName = cm.thisClass().asInternalName();
                String superName = cm.superclass().map(c -> c.asInternalName()).orElse("");
                StringBuilder ifaces = new StringBuilder();
                for (var iface : cm.interfaces()) {
                    if (ifaces.length() > 0) ifaces.append(',');
                    ifaces.append(iface.asInternalName());
                }
                int clsFlags = cm.flags().flagsMask();
                clsW.write(clsName); clsW.write('\t');
                clsW.write(superName); clsW.write('\t');
                clsW.write(ifaces.toString()); clsW.write('\t');
                clsW.write(Integer.toString(clsFlags)); clsW.write('\n');

                for (MethodModel mm : cm.methods()) {
                    methodCount++;
                    String mName = mm.methodName().stringValue();
                    String mDesc = mm.methodType().stringValue();
                    int mFlags = mm.flags().flagsMask();
                    mthW.write(clsName); mthW.write('\t');
                    mthW.write(mName); mthW.write('\t');
                    mthW.write(mDesc); mthW.write('\t');
                    mthW.write(Integer.toString(mFlags)); mthW.write('\n');

                    mm.code().ifPresent(code -> {
                        for (CodeElement el : code) {
                            if (el instanceof InvokeInstruction inv) {
                                try {
                                    calW.write(clsName); calW.write('\t');
                                    calW.write(mName); calW.write('\t');
                                    calW.write(mDesc); calW.write('\t');
                                    calW.write(inv.opcode().name()); calW.write('\t');
                                    calW.write(inv.owner().asInternalName()); calW.write('\t');
                                    calW.write(inv.name().stringValue()); calW.write('\t');
                                    calW.write(inv.type().stringValue()); calW.write('\n');
                                } catch (IOException ioe) {
                                    throw new UncheckedIOException(ioe);
                                }
                            }
                        }
                    });
                }
            }
        }
        double elapsed = (System.nanoTime() - t0) / 1e9;
        System.out.printf("Parsed zombie/**/*.class in %.2fs | classes=%d | methods=%d | parseErrors=%d%n",
                elapsed, classCount, methodCount, parseErrors);
    }
}
