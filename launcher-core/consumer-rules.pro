# Host apps can initialize the runtime reflectively via launcher-sdk-api.
-keep class app.lawnchair.LawnchairHostRuntime {
    public static void initialize(android.app.Application);
}
