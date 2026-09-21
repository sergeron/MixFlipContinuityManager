package com.sergeron.mixflipcontinuity;

import android.content.AttributionSource;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import java.lang.reflect.Method;

/**
 * Mostek CLI uruchamiany przez app_process z poziomu powłoki Shizuku (UID 2000),
 * umożliwiający bezpośrednie operacje na ContinuityProvider, w tym fizyczne usuwanie rekordów
 * (które wymaga przekazania QUERY_ARG_SQL_SELECTION_ARGS niedostępnego w standardowym /system/bin/content).
 */
public class ContinuityDbBridge {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Brak argumentów. Użycie: delete <pkgName> [userId]");
            System.exit(1);
        }

        String action = args[0];
        if ("delete".equalsIgnoreCase(action)) {
            if (args.length < 2) {
                System.err.println("Brak pakietu. Użycie: delete <pkgName> [userId]");
                System.exit(1);
            }
            String pkgName = args[1];
            int userId = 0;
            if (args.length >= 3) {
                try {
                    userId = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {}
            }

            boolean ok = deletePackageRow(pkgName, userId);
            if (ok) {
                System.out.println("DELETE_SUCCESS:" + pkgName);
                System.exit(0);
            } else {
                System.err.println("DELETE_FAILED:" + pkgName);
                System.exit(2);
            }
        } else {
            System.err.println("Nieznana akcja: " + action);
            System.exit(1);
        }
    }

    public static boolean deletePackageRow(String pkgName, int userId) {
        IBinder token = new Binder();
        Object am = null;
        try {
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            Method getService = amClass.getMethod("getService");
            am = getService.invoke(null);

            Method getContentProviderExternal = am.getClass().getMethod(
                    "getContentProviderExternal", String.class, int.class, IBinder.class, String.class
            );
            Object holder = getContentProviderExternal.invoke(
                    am, "com.android.settings.continuity.ContinuityProvider", userId, token, "*cmd*"
            );
            if (holder == null) {
                System.err.println("Nie udało się uzyskać ContentProviderHolder");
                return false;
            }

            Object provider = holder.getClass().getField("provider").get(holder);
            if (provider == null) {
                System.err.println("Pole provider jest null");
                return false;
            }

            AttributionSource attributionSource = new AttributionSource.Builder(Process.myUid())
                    .setPackageName("com.android.shell")
                    .build();

            Bundle extras = new Bundle();
            extras.putStringArray(
                    "android:query-arg-sql-selection-args",
                    new String[]{pkgName, String.valueOf(userId)}
            );

            Uri uri = Uri.parse("content://com.android.settings.continuity.ContinuityProvider/packages");
            Method deleteMethod = provider.getClass().getMethod(
                    "delete", AttributionSource.class, Uri.class, Bundle.class
            );

            int deletedCount = (Integer) deleteMethod.invoke(provider, attributionSource, uri, extras);
            System.out.println("Usunięto rekordów: " + deletedCount);
            return deletedCount > 0;
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            return false;
        } finally {
            if (am != null) {
                try {
                    Method removeMethod = am.getClass().getMethod(
                            "removeContentProviderExternalAsUser", String.class, IBinder.class, int.class
                    );
                    removeMethod.invoke(am, "com.android.settings.continuity.ContinuityProvider", token, userId);
                } catch (Throwable ignored) {}
            }
        }
    }
}
