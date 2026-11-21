# The framework discovers this class through META-INF/xposed/java_init.list rather than a normal
# Java reference. Keep the entry class and its constructor while allowing R8 to optimize its body.
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
    public boolean onHotReloading(io.github.libxposed.api.XposedModuleInterface$HotReloadingParam);
    public void onHotReloaded(io.github.libxposed.api.XposedModuleInterface$HotReloadedParam);
}

# Optional annotations are not part of the module runtime.
-dontwarn io.github.libxposed.annotation.**
