package emberroutes;

import me.zed_0xff.zombie_buddy.Exposer;

/**
 * ZombieBuddy Mod 入口：
 * 所有 {@code @Patch} 补丁类统一直接置于根包 {@code emberroutes} 下，
 * 由 ZombieBuddy 原生 {@code Loader} 通过 {@code g_known_jars} 自动单次扫描织入。
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Exposer.exposeClass(EmberRoutesBridge.class, "EmberRoutesJava");
        System.out.println("[EmberRoutes] JAR loaded, EmberRoutesJava exposed");
    }
}
