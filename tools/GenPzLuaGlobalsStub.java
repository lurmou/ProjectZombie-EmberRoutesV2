import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 零依赖 JDK 25 Tier 1 原版 PZ B42 Lua 全局存根生成器：
 * 仅解析 zombie/Lua/LuaManager$GlobalObject 的全部 public static 全局函数（含 ACC_VARARGS 处理），
 * 生成 ~58 KB 轻量化 ---@meta 存根文件供 LuaLS 使用（不导出全暴露类，避免拖垮 LuaLS）。
 * 用法：java tools/GenPzLuaGlobalsStub.java [jarPath] [outFile]
 */
public class GenPzLuaGlobalsStub {

    private static final Set<String> LUA_KEYWORDS = Set.of(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "goto", "if", "in", "local", "nil", "not", "or",
        "repeat", "return", "then", "true", "until", "while"
    );

    private static String mapType(ClassDesc cd) {
        if (cd == null) return "any";
        if (cd.isPrimitive()) {
            String d = cd.descriptorString();
            if ("V".equals(d)) return null;
            if ("Z".equals(d)) return "boolean";
            if ("C".equals(d)) return "string";
            return "number";
        }
        if (cd.isArray()) return "table";
        String desc = cd.descriptorString();
        String internal = desc.substring(1, desc.length() - 1);
        return switch (internal) {
            case "java/lang/String", "java/lang/CharSequence" -> "string";
            case "java/lang/Boolean" -> "boolean";
            case "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double",
                 "java/lang/Short", "java/lang/Byte", "java/lang/Number" -> "number";
            case "se/krka/kahlua/vm/KahluaTable", "java/util/List", "java/util/ArrayList",
                 "java/util/Map", "java/util/HashMap", "java/util/Set", "java/util/Collection" -> "table";
            default -> "any";
        };
    }

    private static List<String> getParamNames(MethodModel mm, int count) {
        List<String> names = new ArrayList<>(count);
        for (MethodElement el : mm) {
            if (el instanceof MethodParametersAttribute mpa) {
                for (MethodParameterInfo mpi : mpa.parameters()) {
                    String n = mpi.name().map(Utf8Entry::stringValue).orElse(null);
                    if (n != null && !n.isEmpty() && !LUA_KEYWORDS.contains(n)) {
                        names.add(n);
                    }
                }
                break;
            }
        }
        if (names.size() == count) return names;
        names.clear();
        for (int i = 0; i < count; i++) names.add("arg" + i);
        return names;
    }

    private static String getLuaMethodName(MethodModel mm) {
        for (MethodElement el : mm) {
            if (el instanceof RuntimeVisibleAnnotationsAttribute rva) {
                for (Annotation ann : rva.annotations()) {
                    if (ann.className().stringValue().contains("LuaMethod")) {
                        for (AnnotationElement ae : ann.elements()) {
                            if ("name".equals(ae.name().stringValue())
                                    && ae.value() instanceof AnnotationValue.OfString s
                                    && !s.stringValue().isEmpty()) {
                                return s.stringValue();
                            }
                        }
                    }
                }
            }
        }
        return mm.methodName().stringValue();
    }

    public static void main(String[] args) throws Exception {
        String jarPath = args.length > 0
                ? args[0]
                : "D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/projectzomboid.jar";
        Path outFile = Paths.get(args.length > 1 ? args[1] : ".cache/pz-stubs/pz_b42_globals.lua");
        Files.createDirectories(outFile.getParent());

        ClassFile cf = ClassFile.of();
        Map<String, List<MethodModel>> globalFns = new TreeMap<>();

        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry goEntry = jar.getJarEntry("zombie/Lua/LuaManager$GlobalObject.class");
            if (goEntry == null) {
                throw new IllegalStateException("Missing zombie/Lua/LuaManager$GlobalObject.class in " + jarPath);
            }
            ClassModel goModel = cf.parse(jar.getInputStream(goEntry).readAllBytes());
            for (MethodModel mm : goModel.methods()) {
                int fl = mm.flags().flagsMask();
                if ((fl & ClassFile.ACC_PUBLIC) == 0 || (fl & ClassFile.ACC_STATIC) == 0) continue;
                if ((fl & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0) continue;
                String name = getLuaMethodName(mm);
                if (LUA_KEYWORDS.contains(name)) continue;
                globalFns.computeIfAbsent(name, k -> new ArrayList<>()).add(mm);
            }
        }

        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write("---@meta\n-- Auto-generated Tier 1 PZ B42 GlobalObject stubs (do not edit manually).\n\n");
            for (var entry : globalFns.entrySet()) {
                String fnName = entry.getKey();
                List<MethodModel> overloads = entry.getValue();
                overloads.sort(Comparator.comparingInt(m -> m.methodTypeSymbol().parameterCount()));

                for (int i = 1; i < overloads.size(); i++) {
                    MethodModel om = overloads.get(i);
                    MethodTypeDesc mtd = om.methodTypeSymbol();
                    int pCount = mtd.parameterCount();
                    boolean isVarargs = (om.flags().flagsMask() & ClassFile.ACC_VARARGS) != 0 && pCount > 0;
                    List<String> pnames = getParamNames(om, pCount);
                    StringBuilder sig = new StringBuilder("---@overload fun(");
                    for (int j = 0; j < pCount; j++) {
                        if (j > 0) sig.append(", ");
                        if (isVarargs && j == pCount - 1) {
                            sig.append("...: any");
                        } else {
                            String pt = mapType(mtd.parameterType(j));
                            sig.append(pnames.get(j)).append(": ").append(pt == null ? "any" : pt);
                        }
                    }
                    sig.append(")");
                    String rt = mapType(mtd.returnType());
                    if (rt != null) sig.append(": ").append(rt);
                    w.write(sig.toString());
                    w.newLine();
                }

                MethodModel primary = overloads.get(0);
                MethodTypeDesc mtd = primary.methodTypeSymbol();
                int pCount = mtd.parameterCount();
                boolean isVarargs = (primary.flags().flagsMask() & ClassFile.ACC_VARARGS) != 0 && pCount > 0;
                List<String> pnames = getParamNames(primary, pCount);
                List<String> sigArgs = new ArrayList<>(pCount);
                for (int j = 0; j < pCount; j++) {
                    if (isVarargs && j == pCount - 1) {
                        w.write("---@param ... any");
                        w.newLine();
                        sigArgs.add("...");
                    } else {
                        String pt = mapType(mtd.parameterType(j));
                        w.write("---@param " + pnames.get(j) + " " + (pt == null ? "any" : pt));
                        w.newLine();
                        sigArgs.add(pnames.get(j));
                    }
                }
                String rt = mapType(mtd.returnType());
                if (rt != null) {
                    w.write("---@return " + rt);
                    w.newLine();
                }
                w.write("function " + fnName + "(" + String.join(", ", sigArgs) + ") end");
                w.newLine();
                w.newLine();
            }
        }

        System.out.printf("Generated %d GlobalObject stubs -> %s (%d bytes)%n",
                globalFns.size(), outFile, Files.size(outFile));
    }
}
